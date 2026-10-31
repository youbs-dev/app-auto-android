package fr.brice.scenictrajets

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

private const val TAG = "ScenicTrajet"
private const val CHANNEL_ID = "trajet"
private const val NOTIFICATION_ID = 1

/** Vitesse au-delà de laquelle on considère que la voiture roule (≈ 12 km/h). */
private const val MOVING_SPEED_MS = 3.3f
/** Sans conduite détectée pendant ce temps, on arrête de surveiller (économie de batterie). */
private const val WATCH_TIMEOUT_MS = 10 * 60_000L
/** Arrêté plus longtemps que ça : le trajet est terminé. */
private const val STOPPED_TIMEOUT_MS = 10 * 60_000L
/** Un trajet automatique sans Android Auto connecté dans ce délai n'est pas dans le Scénic : abandonné. */
private const val AA_GRACE_MS = 3 * 60_000L

/**
 * Service au premier plan qui garde le GPS actif.
 * Mode automatique : surveille la vitesse, démarre le trajet quand la voiture roule,
 * le termine quand Android Auto se déconnecte ou après 10 min à l'arrêt.
 */
class TripService : Service() {

    companion object {
        const val ACTION_WATCH = "fr.brice.scenictrajets.WATCH"
        const val ACTION_START = "fr.brice.scenictrajets.START"
        const val ACTION_STOP = "fr.brice.scenictrajets.STOP"
        const val ACTION_RESUME = "fr.brice.scenictrajets.RESUME"

        fun send(context: Context, action: String) {
            if (!TripRecorder.hasLocationPermission(context)) return
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, TripService::class.java).setAction(action)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Impossible de démarrer le service ($action)", e)
            }
        }
    }

    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var watchUntil = 0L
    private var lastMoving = 0L
    private var aaConnected = false
    private var aaSeenDuringTrip = false
    private var carConnection: CarConnection? = null

    private val listener = LocationListener { loc: Location -> onLocation(loc) }

    private val aaObserver = Observer<Int> { type ->
        val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION
        Log.i(TAG, "Android Auto connecté : $connected")
        if (connected) {
            aaSeenDuringTrip = true
        } else if (aaConnected && TripRecorder.isActive) {
            // La voiture vient d'être éteinte (ou le téléphone débranché) : fin du trajet.
            endTrip()
            return@Observer
        }
        aaConnected = connected
        updateNotification()
    }

    private val tick = object : Runnable {
        override fun run() {
            checkTimeouts()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        VehicleStore.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trajets", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service au premier plan refusé", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (locationManager == null) {
            try {
                val lm = getSystemService(LocationManager::class.java)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
                locationManager = lm
                carConnection = CarConnection(this).also { it.type.observeForever(aaObserver) }
                handler.postDelayed(tick, 30_000)
            } catch (e: Exception) {
                Log.e(TAG, "GPS indisponible", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_START -> {
                aaSeenDuringTrip = aaConnected
                lastMoving = System.currentTimeMillis()
                TripRecorder.begin(this, auto = false)
            }
            ACTION_RESUME -> TripRecorder.resumePrevious(this)
            ACTION_STOP -> {
                TripRecorder.finish(this)
                stopSelf()
            }
            else -> watchUntil = System.currentTimeMillis() + WATCH_TIMEOUT_MS
        }
        updateNotification()
        return START_NOT_STICKY
    }

    private fun onLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val moving = loc.hasSpeed() && loc.speed >= MOVING_SPEED_MS && loc.accuracy <= 50f
        if (moving) {
            lastMoving = now
            TripRecorder.lastMovingAt = now
        }
        if (!TripRecorder.isActive && moving && now < watchUntil) {
            aaSeenDuringTrip = aaConnected
            TripRecorder.begin(this, auto = true)
            updateNotification()
        }
        TripRecorder.onLocation(this, loc)
    }

    private fun checkTimeouts() {
        val now = System.currentTimeMillis()
        // Retire le bouton « Reprendre » de la notification quand la proposition expire.
        if (TripRecorder.isActive) updateNotification()
        if (TripRecorder.isActive) {
            if (TripRecorder.isAuto && !aaSeenDuringTrip && now - TripRecorder.startTime > AA_GRACE_MS) {
                Log.i(TAG, "Pas d'Android Auto : trajet abandonné (pas dans le Scénic)")
                TripRecorder.discard(this)
                stopSelf()
            } else if (now - lastMoving > STOPPED_TIMEOUT_MS) {
                endTrip()
            }
        } else if (now > watchUntil) {
            stopSelf()
        }
    }

    private fun endTrip() {
        TripRecorder.finish(this)
        aaConnected = false
        // On surveille encore un peu, au cas où la voiture repart.
        watchUntil = System.currentTimeMillis() + WATCH_TIMEOUT_MS
        updateNotification()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (TripRecorder.isActive) "Trajet en cours d'enregistrement" else "En attente du départ…"
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
        TripRecorder.resumeOffer?.let { prev ->
            b.setContentText("Le trajet précédent s'est arrêté à " + TripStats.clock(prev.end) + ". Le reprendre ?")
            val resume = PendingIntent.getService(
                this, 1, Intent(this, TripService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_car), "Reprendre le trajet précédent", resume).build()
            )
        }
        return b.build()
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        carConnection?.type?.removeObserver(aaObserver)
        locationManager?.let { lm -> runCatching { lm.removeUpdates(listener) } }
        // Service arrêté par le système en plein trajet : on garde ce qui a été enregistré.
        TripRecorder.finish(this)
        super.onDestroy()
    }
}
