package fr.brice.scenictrajets

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * Écran d'accueil dans la voiture : le trajet (en cours ou prêt), la batterie,
 * le total du jour, et un gros bouton Démarrer / Arrêter.
 */
class HomeScreen(carContext: CarContext) : Screen(carContext) {

    private val handler = Handler(Looper.getMainLooper())
    private var refreshPending = false
    private var lastMissingCount = -1

    val car = CarData(carContext) { refresh() }
    private val tripListener: () -> Unit = { handler.post { refresh() } }

    /** Pendant un trajet, met à jour la durée même sans nouvelle position. */
    private val ticker = object : Runnable {
        override fun run() {
            if (TripRecorder.isActive) invalidate()
            handler.postDelayed(this, 60_000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                TripRecorder.addListener(tripListener)
                car.start()
            }
            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, 60_000)
            }
            override fun onStop(owner: LifecycleOwner) = handler.removeCallbacks(ticker)
            override fun onDestroy(owner: LifecycleOwner) {
                TripRecorder.removeListener(tripListener)
                car.stop()
            }
        })
    }

    /** Limite les rafraîchissements de l'écran (Android Auto en limite le nombre). */
    private fun refresh() {
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed({ refreshPending = false; invalidate() }, 1000)
    }

    override fun onGetTemplate(): Template {
        val missing = car.missingPermissions()
        if (lastMissingCount >= 0 && missing.size < lastMissingCount) car.restart()
        lastMissingCount = missing.size

        val pane = Pane.Builder()
            .addRow(tripRow())
            .addRow(batteryRow(missing.isNotEmpty()))
            .addRow(todayRow())
        if (missing.isNotEmpty() || !TripRecorder.hasLocationPermission(carContext)) {
            pane.addRow(
                Row.Builder()
                    .setTitle("⚠️ Autorisations manquantes")
                    .addText("Ouvre Scénic Trajets sur le téléphone, ou Diagnostic à l'arrêt")
                    .build()
            )
        }
        pane.addAction(tripAction())
        pane.addAction(
            Action.Builder()
                .setTitle("Trajets")
                .setOnClickListener { screenManager.push(TripsScreen(carContext)) }
                .build()
        )

        return PaneTemplate.Builder(pane.build())
            .setTitle("Scénic Trajets")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Diagnostic")
                            .setOnClickListener { screenManager.push(DiagnosticScreen(carContext, this)) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun tripRow(): Row = if (TripRecorder.isActive) {
        Row.Builder()
            .setTitle(if (TripRecorder.isAuto) "🔴 Trajet en cours (automatique)" else "🔴 Trajet en cours")
            .addText(TripStats.current())
            .build()
    } else {
        Row.Builder()
            .setTitle("Aucun trajet en cours")
            .addText(
                if (DrivingDetector.isEnabled(carContext)) "Il démarrera tout seul dès que la voiture roule"
                else "Touche Démarrer pour enregistrer le parcours"
            )
            .build()
    }

    private fun batteryRow(permissionMissing: Boolean): Row {
        val pct = CarSnapshot.batteryPercent
        val range = CarSnapshot.rangeKm
        val text = when {
            pct != null && range != null ->
                String.format(Locale.FRANCE, "%.0f %% · %.0f km d'autonomie", pct, range)
            pct != null -> String.format(Locale.FRANCE, "%.0f %%", pct)
            permissionMissing -> "Accès non autorisé"
            else -> "En attente de la voiture…"
        }
        return Row.Builder().setTitle("🔋 Batterie").addText(text).build()
    }

    private fun todayRow(): Row {
        val trips = runCatching { TripRecorder.trips(carContext) }.getOrDefault(emptyList())
        return Row.Builder()
            .setTitle("Aujourd'hui")
            .addText(TripStats.total(trips, TripStats.startOfDay(System.currentTimeMillis())))
            .build()
    }

    private fun tripAction(): Action = if (TripRecorder.isActive) {
        Action.Builder()
            .setTitle("Arrêter")
            .setBackgroundColor(CarColor.RED)
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener { toggleTrip() }
            .build()
    } else {
        Action.Builder()
            .setTitle("Démarrer")
            .setBackgroundColor(CarColor.GREEN)
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener { toggleTrip() }
            .build()
    }

    private fun toggleTrip() {
        try {
            if (TripRecorder.isActive) {
                TripService.send(carContext, TripService.ACTION_STOP)
                CarToast.makeText(carContext, "Trajet enregistré", CarToast.LENGTH_SHORT).show()
            } else if (!TripRecorder.hasLocationPermission(carContext)) {
                CarToast.makeText(
                    carContext,
                    "Autorise la localisation dans l'app Scénic Trajets sur le téléphone",
                    CarToast.LENGTH_LONG,
                ).show()
            } else {
                TripService.send(carContext, TripService.ACTION_START)
                CarToast.makeText(carContext, "Trajet démarré", CarToast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ScenicTest", "Trajet : erreur", e)
            CarToast.makeText(carContext, "Erreur : ${e.javaClass.simpleName}", CarToast.LENGTH_LONG).show()
        }
        invalidate()
    }
}
