package fr.brice.scenictrajets

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

private const val TAG = "ScenicTest"

internal val CAR_PERMISSIONS = listOf(
    "com.google.android.gms.permission.CAR_FUEL",
    "com.google.android.gms.permission.CAR_SPEED",
    "com.google.android.gms.permission.CAR_MILEAGE",
)

/**
 * Écran de diagnostic : affiche en direct ce que la voiture transmet à Android Auto.
 * Chaque ligne indique soit la valeur, soit la raison pour laquelle elle manque.
 */
class VehicleDataScreen(carContext: CarContext) : Screen(carContext) {

    private val executor = ContextCompat.getMainExecutor(carContext)
    private val carInfo: CarInfo =
        carContext.getCarService(CarHardwareManager::class.java).carInfo

    private var vehicule = "en attente…"
    private var batterie = "en attente…"
    private var autonomie = "en attente…"
    private var vitesse = "en attente…"
    private var kilometrage = "en attente…"

    private val handler = Handler(Looper.getMainLooper())
    private var refreshPending = false

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { e ->
        CarSnapshot.batteryPercent = ok(e.batteryPercent)
        CarSnapshot.rangeKm = ok(e.rangeRemainingMeters)?.div(1000f)
        batterie = format(e.batteryPercent) { String.format(Locale.FRANCE, "%.1f %%", it) }
        autonomie = format(e.rangeRemainingMeters) { String.format(Locale.FRANCE, "%.0f km", it / 1000f) }
        Log.i(TAG, "batterie=$batterie autonomie=$autonomie")
        refresh()
    }

    private val speedListener = OnCarDataAvailableListener<Speed> { s ->
        vitesse = format(s.rawSpeedMetersPerSecond) { String.format(Locale.FRANCE, "%.0f km/h", it * 3.6f) }
        refresh()
    }

    private val mileageListener = OnCarDataAvailableListener<Mileage> { m ->
        // Malgré son nom, la valeur arrive déjà en km sur le Scénic (13 300 km vérifiés au tableau de bord).
        CarSnapshot.odometerKm = ok(m.odometerMeters)
        kilometrage = format(m.odometerMeters) { String.format(Locale.FRANCE, "%,.0f km", it) }
        Log.i(TAG, "kilometrage=$kilometrage")
        refresh()
    }

    private val tripListener: () -> Unit = { refresh() }
    private var listening = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!listening) startListening()
            }
            // Pendant un trajet, on continue de lire la batterie même si un autre écran est affiché.
            override fun onStop(owner: LifecycleOwner) {
                if (!TripRecorder.isActive) stopListening()
            }
            override fun onCreate(owner: LifecycleOwner) = TripRecorder.addListener(tripListener)
            override fun onDestroy(owner: LifecycleOwner) {
                TripRecorder.removeListener(tripListener)
                stopListening()
            }
        })
    }

    private fun startListening() {
        listening = true
        safely("modèle") {
            carInfo.fetchModel(executor) { m: Model ->
                val nom = listOfNotNull(m.manufacturer.value, m.name.value, m.year.value?.toString())
                    .joinToString(" ")
                vehicule = nom.ifBlank { statusText(m.name.status) }
                fetchEnergyProfile()
            }
        }
        safely("batterie") { carInfo.addEnergyLevelListener(executor, energyListener) }
        safely("vitesse") { carInfo.addSpeedListener(executor, speedListener) }
        safely("kilométrage") { carInfo.addMileageListener(executor, mileageListener) }
    }

    private fun fetchEnergyProfile() {
        safely("profil énergie") {
            carInfo.fetchEnergyProfile(executor) { p: EnergyProfile ->
                val types = p.fuelTypes.value
                    ?.joinToString { if (it == EnergyProfile.FUEL_TYPE_ELECTRIC) "électrique" else "type $it" }
                vehicule += " · " + (types ?: statusText(p.fuelTypes.status))
                Log.i(TAG, "vehicule=$vehicule")
                refresh()
            }
        }
    }

    private fun stopListening() {
        listening = false
        runCatching { carInfo.removeEnergyLevelListener(energyListener) }
        runCatching { carInfo.removeSpeedListener(speedListener) }
        runCatching { carInfo.removeMileageListener(mileageListener) }
    }

    /** Une permission manquante ne doit pas faire planter l'app : on l'affiche à la place. */
    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission refusée pour $what", e)
            when (what) {
                "batterie" -> { batterie = "permission refusée"; autonomie = "permission refusée" }
                "vitesse" -> vitesse = "permission refusée"
                "kilométrage" -> kilometrage = "permission refusée"
                else -> vehicule = "permission refusée"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pour $what", e)
        }
    }

    /** Limite les rafraîchissements de l'écran (Android Auto en limite le nombre). */
    private fun refresh() {
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed({ refreshPending = false; invalidate() }, 1000)
    }

    private fun ok(v: CarValue<Float>): Float? =
        if (v.status == CarValue.STATUS_SUCCESS) v.value else null

    private fun <T> format(v: CarValue<T>, fmt: (T) -> String): String {
        val value = v.value
        return if (v.status == CarValue.STATUS_SUCCESS && value != null) fmt(value) else statusText(v.status)
    }

    private fun statusText(status: Int) = when (status) {
        CarValue.STATUS_SUCCESS -> "OK (vide)"
        CarValue.STATUS_UNIMPLEMENTED -> "non transmis par la voiture"
        CarValue.STATUS_UNAVAILABLE -> "indisponible pour l'instant"
        else -> "inconnu"
    }

    private var lastMissingCount = -1
    private var permissionError: String? = null

    /** Demande les autorisations depuis la voiture ; en cas d'échec, l'erreur s'affiche au lieu de planter. */
    private fun requestCarPermissions(missing: List<String>) {
        try {
            carContext.requestPermissions(missing) { granted, rejected ->
                CarToast.makeText(
                    carContext,
                    "Accordées : ${granted.size}, refusées : ${rejected.size}",
                    CarToast.LENGTH_LONG,
                ).show()
                stopListening()
                startListening()
                invalidate()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Demande d'autorisations impossible", e)
            permissionError = e.javaClass.simpleName + (e.message?.let { " : $it" } ?: "")
            CarToast.makeText(
                carContext,
                "Ouvre l'app Scénic Trajets sur le téléphone pour autoriser l'accès",
                CarToast.LENGTH_LONG,
            ).show()
            invalidate()
        }
    }

    private fun missingPermissions() = CAR_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED
    }

    override fun onGetTemplate(): Template {
        val missing = missingPermissions()
        if (lastMissingCount >= 0 && missing.size < lastMissingCount) {
            // Des autorisations viennent d'être accordées (par exemple depuis le téléphone) : on relance la lecture.
            stopListening()
            startListening()
        }
        lastMissingCount = missing.size

        val permRow = Row.Builder()
            .setTitle("Permissions")
            .addText(
                when {
                    missing.isEmpty() -> "toutes accordées"
                    permissionError != null -> "erreur : $permissionError"
                    else -> "${missing.size} manquante(s) : toucher pour demander, ou ouvrir l'app sur le téléphone"
                }
            )
        if (missing.isNotEmpty()) {
            permRow.setOnClickListener(ParkedOnlyOnClickListener.create { requestCarPermissions(missing) })
        }

        val list = ItemList.Builder()
            .addItem(tripRow())
            .addItem(row("Véhicule", vehicule))
            .addItem(row("Batterie", batterie))
            .addItem(row("Autonomie", autonomie))
            .addItem(row("Vitesse", vitesse))
            .addItem(row("Kilométrage", kilometrage))
            .addItem(permRow.build())
            .build()

        return ListTemplate.Builder()
            .setTitle("Données du véhicule")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list)
            .build()
    }

    private fun tripRow(): Row {
        val builder = Row.Builder()
        if (TripRecorder.isActive) {
            val min = (System.currentTimeMillis() - TripRecorder.startTime) / 60_000
            val mode = if (TripRecorder.isAuto) "automatique" else "manuel"
            builder.setTitle("⏹ Arrêter le trajet")
                .addText(String.format(Locale.FRANCE, "En cours (%s) : %.1f km · %d min", mode, TripRecorder.distanceKm, min))
        } else {
            builder.setTitle("▶ Démarrer un trajet")
                .addText(
                    if (DrivingDetector.isEnabled(carContext)) "Démarrage automatique dès que la voiture roule"
                    else "Enregistre le parcours GPS, la batterie et l'autonomie"
                )
        }
        builder.setOnClickListener { toggleTrip() }
        return builder.build()
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
            Log.e(TAG, "Trajet : erreur", e)
            CarToast.makeText(carContext, "Erreur : ${e.javaClass.simpleName}", CarToast.LENGTH_LONG).show()
        }
        invalidate()
    }

    private fun row(title: String, value: String) =
        Row.Builder().setTitle(title).addText(value).build()
}
