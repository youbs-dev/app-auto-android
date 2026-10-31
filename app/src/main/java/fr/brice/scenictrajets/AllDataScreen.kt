package fr.brice.scenictrajets

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.EnergyProfile
import androidx.car.app.hardware.info.EvStatus
import androidx.car.app.hardware.info.Gyroscope
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.car.app.hardware.info.Speed
import androidx.car.app.hardware.info.TollCard
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * Toutes les données qu'Android Auto peut transmettre, avec leur état :
 * valeur, « non transmis par la voiture », « indisponible » ou « permission refusée ».
 */
class AllDataScreen(carContext: CarContext) : Screen(carContext) {

    private val executor = ContextCompat.getMainExecutor(carContext)
    private val hardware = carContext.getCarService(CarHardwareManager::class.java)
    private val info = hardware.carInfo
    private val sensors = hardware.carSensors
    private val handler = Handler(Looper.getMainLooper())
    private var refreshPending = false

    /** Valeurs affichées, dans l'ordre d'arrivée des catégories. */
    private val values = linkedMapOf<String, String>()

    private val energy = OnCarDataAvailableListener<EnergyLevel> { e ->
        put("Batterie", v(e.batteryPercent) { pct(it) })
        put("Carburant", v(e.fuelPercent) { pct(it) })
        put("Énergie basse", v(e.energyIsLow) { if (it) "oui" else "non" })
        put("Autonomie", v(e.rangeRemainingMeters) { String.format(Locale.FRANCE, "%.0f (unité envoyée : m)", it) })
        put("Unité de distance", v(e.distanceDisplayUnit) { "code $it" })
    }
    private val speed = OnCarDataAvailableListener<Speed> { s ->
        put("Vitesse brute", v(s.rawSpeedMetersPerSecond) { String.format(Locale.FRANCE, "%.1f km/h", it * 3.6f) })
        put("Vitesse affichée", v(s.displaySpeedMetersPerSecond) { String.format(Locale.FRANCE, "%.1f km/h", it * 3.6f) })
        put("Unité de vitesse", v(s.speedDisplayUnit) { "code $it" })
    }
    private val mileage = OnCarDataAvailableListener<Mileage> { m ->
        put("Compteur", v(m.odometerMeters) { String.format(Locale.FRANCE, "%,.0f (unité envoyée : m, en réalité km)", it) })
        put("Unité du compteur", v(m.distanceDisplayUnit) { "code $it" })
    }
    private val toll = OnCarDataAvailableListener<TollCard> { t ->
        put("Badge de péage", v(t.cardState) {
            when (it) {
                TollCard.TOLLCARD_STATE_VALID -> "valide"
                TollCard.TOLLCARD_STATE_INVALID -> "invalide"
                TollCard.TOLLCARD_STATE_NOT_INSERTED -> "absent"
                else -> "inconnu"
            }
        })
    }
    private val ev = OnCarDataAvailableListener<EvStatus> { s ->
        put("Trappe de recharge ouverte", v(s.evChargePortOpen) { if (it) "oui" else "non" })
        put("Câble de recharge branché", v(s.evChargePortConnected) { if (it) "oui" else "non" })
    }
    private val compass = OnCarDataAvailableListener<Compass> { c ->
        put("Boussole", v(c.orientations) { list(it, "°") })
    }
    private val accelerometer = OnCarDataAvailableListener<Accelerometer> { a ->
        put("Accéléromètre", v(a.forces) { list(it, " m/s²") })
    }
    private val gyroscope = OnCarDataAvailableListener<Gyroscope> { g ->
        put("Gyroscope", v(g.rotations) { list(it, " rad/s") })
    }
    private val location = OnCarDataAvailableListener<CarHardwareLocation> { l ->
        put("Position (GPS de la voiture)", v(l.location) {
            String.format(Locale.US, "%.5f, %.5f · ±%.0f m", it.latitude, it.longitude, it.accuracy)
        })
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = startAll()
            override fun onDestroy(owner: LifecycleOwner) = stopAll()
        })
    }

    @OptIn(markerClass = [ExperimentalCarApi::class])
    private fun startAll() {
        listOf(
            "Modèle", "Profil énergie", "Batterie", "Carburant", "Énergie basse", "Autonomie", "Unité de distance",
            "Vitesse brute", "Vitesse affichée", "Unité de vitesse", "Compteur", "Unité du compteur",
            "Badge de péage", "Trappe de recharge ouverte", "Câble de recharge branché",
            "Boussole", "Accéléromètre", "Gyroscope", "Position (GPS de la voiture)",
        ).forEach { values[it] = "en attente…" }

        safely(listOf("Modèle")) {
            info.fetchModel(executor) { m: Model ->
                put("Modèle", listOf(
                    "fabricant " + v(m.manufacturer) { it },
                    "nom " + v(m.name) { it },
                    "année " + v(m.year) { it.toString() },
                ).joinToString(" · "))
            }
        }
        safely(listOf("Profil énergie")) {
            info.fetchEnergyProfile(executor) { p: EnergyProfile ->
                put("Profil énergie", "carburants " + v(p.fuelTypes) { t -> t.joinToString { fuel(it) } } +
                    " · prises " + v(p.evConnectorTypes) { t -> t.joinToString { "type $it" } })
            }
        }
        safely(listOf("Batterie", "Carburant", "Énergie basse", "Autonomie", "Unité de distance")) {
            info.addEnergyLevelListener(executor, energy)
        }
        safely(listOf("Vitesse brute", "Vitesse affichée", "Unité de vitesse")) { info.addSpeedListener(executor, speed) }
        safely(listOf("Compteur", "Unité du compteur")) { info.addMileageListener(executor, mileage) }
        safely(listOf("Badge de péage")) { info.addTollListener(executor, toll) }
        safely(listOf("Trappe de recharge ouverte", "Câble de recharge branché")) { info.addEvStatusListener(executor, ev) }
        val rate = CarSensors.UPDATE_RATE_NORMAL
        safely(listOf("Boussole")) { sensors.addCompassListener(rate, executor, compass) }
        safely(listOf("Accéléromètre")) { sensors.addAccelerometerListener(rate, executor, accelerometer) }
        safely(listOf("Gyroscope")) { sensors.addGyroscopeListener(rate, executor, gyroscope) }
        safely(listOf("Position (GPS de la voiture)")) { sensors.addCarHardwareLocationListener(rate, executor, location) }
    }

    @OptIn(markerClass = [ExperimentalCarApi::class])
    private fun stopAll() {
        runCatching { info.removeEnergyLevelListener(energy) }
        runCatching { info.removeSpeedListener(speed) }
        runCatching { info.removeMileageListener(mileage) }
        runCatching { info.removeTollListener(toll) }
        runCatching { info.removeEvStatusListener(ev) }
        runCatching { sensors.removeCompassListener(compass) }
        runCatching { sensors.removeAccelerometerListener(accelerometer) }
        runCatching { sensors.removeGyroscopeListener(gyroscope) }
        runCatching { sensors.removeCarHardwareLocationListener(location) }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        values.forEach { (k, value) -> list.addItem(Row.Builder().setTitle(k).addText(value).build()) }
        return ListTemplate.Builder()
            .setTitle("Toutes les données")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun put(key: String, value: String) {
        values[key] = value
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed({ refreshPending = false; invalidate() }, 2000)
    }

    /** Une donnée refusée ou non prise en charge s'affiche au lieu de faire planter l'écran. */
    private fun safely(keys: List<String>, block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            keys.forEach { values[it] = "permission refusée" }
        } catch (e: Throwable) {
            Log.w("ScenicDonnees", "Donnée indisponible : $keys", e)
            keys.forEach { values[it] = "non pris en charge (${e.javaClass.simpleName})" }
        }
    }

    private fun <T> v(c: CarValue<T>, fmt: (T) -> String): String {
        val value = c.value
        return when {
            c.status == CarValue.STATUS_SUCCESS && value != null -> fmt(value)
            c.status == CarValue.STATUS_UNIMPLEMENTED -> "non transmis par la voiture"
            c.status == CarValue.STATUS_UNAVAILABLE -> "indisponible pour l'instant"
            c.status == CarValue.STATUS_SUCCESS -> "vide"
            else -> "inconnu (état ${c.status})"
        }
    }

    private fun pct(v: Float) = String.format(Locale.FRANCE, "%.1f %%", v)

    private fun list(v: List<Float>, unit: String) =
        v.joinToString(" · ") { String.format(Locale.FRANCE, "%.2f", it) } + unit

    private fun fuel(type: Int) = when (type) {
        EnergyProfile.FUEL_TYPE_ELECTRIC -> "électrique"
        EnergyProfile.FUEL_TYPE_UNLEADED -> "sans plomb"
        EnergyProfile.FUEL_TYPE_DIESEL_1, EnergyProfile.FUEL_TYPE_DIESEL_2 -> "diesel"
        else -> "type $type"
    }
}
