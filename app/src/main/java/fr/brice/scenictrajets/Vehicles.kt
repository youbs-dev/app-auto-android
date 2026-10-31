package fr.brice.scenictrajets

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un véhicule suivi par l'app. */
data class Vehicle(
    val id: String,
    val name: String,
    val electric: Boolean = true,
    /** Capacité utile de la batterie, en kWh (véhicule électrique). */
    val batteryKwh: Float = 87f,
    /** Consommation moyenne en L/100 km (véhicule thermique). */
    val fuelPer100: Float = 6.5f,
    /** Modèle annoncé par Android Auto (« Renault HCB 2022 »), pour reconnaître la voiture. */
    val model: String = "",
    /** Créé automatiquement à la première connexion : réglages à vérifier. */
    val toConfigure: Boolean = false,
) {
    /** Identifiant d'appareil envoyé à Dawarich. */
    val deviceId: String get() = id
}

/**
 * Liste des véhicules et véhicule actuellement connecté à Android Auto.
 * Les trajets et recharges d'avant la gestion multi-véhicule appartiennent au Scénic.
 */
object VehicleStore {
    const val DEFAULT_ID = "scenic"
    private const val PREFS = "vehicules"

    @Volatile private var loaded = false
    @Volatile var vehicles: List<Vehicle> = listOf(Vehicle(DEFAULT_ID, "Scénic"))
        private set
    /** Véhicule reconnu lors de la dernière connexion à Android Auto. */
    @Volatile var currentId: String = DEFAULT_ID
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** À appeler au démarrage de chaque partie de l'app (écran, service, voiture). */
    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        val p = prefs(c)
        p.getString("liste", null)?.let { json ->
            runCatching {
                val a = JSONArray(json)
                vehicles = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.ifEmpty { vehicles }
            }
        }
        currentId = p.getString("actuel", DEFAULT_ID)!!.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.first().id
        loaded = true
        runCatching { TripRecorder.migrateVehicleColumn(c) }
    }

    fun get(id: String?): Vehicle = vehicles.firstOrNull { it.id == id } ?: vehicles.first()
    val current: Vehicle get() = get(currentId)
    fun name(id: String?) = get(id).name

    /**
     * Android Auto a donné le modèle de la voiture : on retrouve le véhicule correspondant,
     * ou on en crée un nouveau. Le premier modèle vu est rattaché au Scénic (véhicule d'origine).
     */
    @Synchronized
    fun onCarModel(c: Context, model: String, electric: Boolean?) {
        init(c)
        if (model.isBlank()) return
        val list = vehicles.toMutableList()
        var v = list.firstOrNull { it.model == model }
        if (v == null) {
            val unclaimed = list.firstOrNull { it.model.isEmpty() && it.id == DEFAULT_ID }
            v = if (unclaimed != null) {
                unclaimed.copy(model = model).also { nv -> list[list.indexOf(unclaimed)] = nv }
            } else {
                Vehicle(
                    id = newId(model, list),
                    name = model,
                    electric = electric ?: true,
                    batteryKwh = 50f,
                    model = model,
                    toConfigure = true,
                ).also { list += it }
            }
        } else if (electric != null && v.toConfigure && v.electric != electric) {
            v = v.copy(electric = electric).also { nv -> list[list.indexOf(v)] = nv }
        }
        vehicles = list
        currentId = v.id
        save(c)
    }

    @Synchronized
    fun upsert(c: Context, v: Vehicle) {
        init(c)
        val list = vehicles.toMutableList()
        val i = list.indexOfFirst { it.id == v.id }
        if (i >= 0) list[i] = v else list += v
        vehicles = list
        save(c)
    }

    /** Supprime un véhicule sans trajet. Renvoie false s'il reste des trajets ou si c'est le dernier. */
    @Synchronized
    fun delete(c: Context, id: String): Boolean {
        if (vehicles.size <= 1) return false
        if (TripRecorder.trips(c).any { it.vehicleId == id }) return false
        vehicles = vehicles.filter { it.id != id }
        if (currentId == id) currentId = vehicles.first().id
        save(c)
        return true
    }

    fun newVehicle(c: Context): Vehicle {
        init(c)
        return Vehicle(newId("vehicule", vehicles), "Nouveau véhicule", batteryKwh = 50f)
    }

    private fun newId(base: String, list: List<Vehicle>): String {
        val slug = base.lowercase()
            .replace(Regex("[àâä]"), "a").replace(Regex("[éèêë]"), "e").replace(Regex("[îï]"), "i")
            .replace(Regex("[ôö]"), "o").replace(Regex("[ùûü]"), "u").replace("ç", "c")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "vehicule" }
        var id = slug
        var n = 2
        while (list.any { it.id == id }) id = "$slug-${n++}"
        return id
    }

    private fun save(c: Context) {
        val a = JSONArray()
        vehicles.forEach { a.put(toJson(it)) }
        prefs(c).edit().putString("liste", a.toString()).putString("actuel", currentId).apply()
        notifyListeners()
    }

    private fun toJson(v: Vehicle) = JSONObject()
        .put("id", v.id).put("nom", v.name).put("electrique", v.electric)
        .put("batterie_kwh", v.batteryKwh.toDouble()).put("conso_l100", v.fuelPer100.toDouble())
        .put("modele", v.model).put("a_configurer", v.toConfigure)

    private fun fromJson(o: JSONObject) = Vehicle(
        id = o.getString("id"),
        name = o.optString("nom", "Véhicule"),
        electric = o.optBoolean("electrique", true),
        batteryKwh = o.optDouble("batterie_kwh", 87.0).toFloat(),
        fuelPer100 = o.optDouble("conso_l100", 6.5).toFloat(),
        model = o.optString("modele", ""),
        toConfigure = o.optBoolean("a_configurer", false),
    )
}
