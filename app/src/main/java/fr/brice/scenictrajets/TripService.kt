package fr.brice.scenictrajets

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log

private const val CHANNEL_ID = "trajet"
private const val NOTIFICATION_ID = 1

/** Service au premier plan : garde le GPS actif pendant un trajet, même si un autre écran est affiché. */
class TripService : Service() {

    private lateinit var locationManager: LocationManager

    private val listener = LocationListener { loc: Location -> TripRecorder.onLocation(this, loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Trajet en cours", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Trajet en cours d'enregistrement")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            locationManager = getSystemService(LocationManager::class.java)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 5f, listener, Looper.getMainLooper()
            )
        } catch (e: Exception) {
            // Autorisation de localisation manquante : le trajet garde au moins les données de la voiture.
            Log.e("ScenicTrajet", "GPS indisponible", e)
            TripRecorder.stop(this)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::locationManager.isInitialized) runCatching { locationManager.removeUpdates(listener) }
        super.onDestroy()
    }
}
