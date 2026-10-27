package fr.brice.scenictrajets

import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import java.util.Locale

private const val TAG = "ScenicTest"

internal val CAR_PERMISSIONS = listOf(
    "com.google.android.gms.permission.CAR_FUEL",
    "com.google.android.gms.permission.CAR_SPEED",
    "com.google.android.gms.permission.CAR_MILEAGE",
)

/**
 * Lit en continu ce que la voiture transmet à Android Auto (batterie, autonomie, compteur…).
 * Les textes servent à l'écran Diagnostic ; les valeurs sont copiées dans [CarSnapshot] pour les trajets.
 */
class CarData(private val carContext: CarContext, private val onChange: () -> Unit) {

    private val executor = ContextCompat.getMainExecutor(carContext)
    private val carInfo: CarInfo =
        carContext.getCarService(CarHardwareManager::class.java).carInfo

    var vehicule = "en attente…"; private set
    var batterie = "en attente…"; private set
    var autonomie = "en attente…"; private set
    var vitesse = "en attente…"; private set
    var kilometrage = "en attente…"; private set

    private var listening = false

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { e ->
        CarSnapshot.batteryPercent = ok(e.batteryPercent)
        CarSnapshot.rangeKm = ok(e.rangeRemainingMeters)?.div(1000f)
        batterie = format(e.batteryPercent) { String.format(Locale.FRANCE, "%.1f %%", it) }
        autonomie = format(e.rangeRemainingMeters) { String.format(Locale.FRANCE, "%.0f km", it / 1000f) }
        Log.i(TAG, "batterie=$batterie autonomie=$autonomie")
        onChange()
    }

    private val speedListener = OnCarDataAvailableListener<Speed> { s ->
        vitesse = format(s.rawSpeedMetersPerSecond) { String.format(Locale.FRANCE, "%.0f km/h", it * 3.6f) }
        onChange()
    }

    private val mileageListener = OnCarDataAvailableListener<Mileage> { m ->
        // Malgré son nom, la valeur arrive déjà en km sur le Scénic (13 300 km vérifiés au tableau de bord).
        CarSnapshot.odometerKm = ok(m.odometerMeters)
        kilometrage = format(m.odometerMeters) { String.format(Locale.FRANCE, "%,.0f km", it) }
        onChange()
    }

    fun start() {
        if (listening) return
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

    fun stop() {
        listening = false
        runCatching { carInfo.removeEnergyLevelListener(energyListener) }
        runCatching { carInfo.removeSpeedListener(speedListener) }
        runCatching { carInfo.removeMileageListener(mileageListener) }
    }

    fun restart() { stop(); start() }

    fun missingPermissions() = CAR_PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun fetchEnergyProfile() {
        safely("profil énergie") {
            carInfo.fetchEnergyProfile(executor) { p: EnergyProfile ->
                val types = p.fuelTypes.value
                    ?.joinToString { if (it == EnergyProfile.FUEL_TYPE_ELECTRIC) "électrique" else "type $it" }
                vehicule += " · " + (types ?: statusText(p.fuelTypes.status))
                onChange()
            }
        }
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
}
