package fr.brice.scenictrajets

import android.content.Context
import android.location.Location
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Prix de l'électricité et de l'essence (pour la comparaison), réglés par l'utilisateur. */
object PriceSettings {
    private const val PREFS = "prix"

    /** Une partie de l'électricité est perdue pendant la recharge (environ 10 %). */
    const val CHARGE_EFFICIENCY = 0.9f

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Prix du kWh à la maison (heures pleines ou tarif unique). */
    fun homePrice(c: Context) = prefs(c).getFloat("kwh", 0.25f)
    /** Prix du kWh en heures creuses, 0 si pas d'option heures creuses. */
    fun offPeakPrice(c: Context) = prefs(c).getFloat("kwh_hc", 0f)
    fun fuelPrice(c: Context) = prefs(c).getFloat("essence_prix", 1.80f)
    fun fuelPer100(c: Context) = prefs(c).getFloat("essence_conso", 6.5f)

    /** Prix appliqué à une recharge à la maison : heures creuses si renseigné. */
    fun defaultChargePrice(c: Context) = offPeakPrice(c).takeIf { it > 0f } ?: homePrice(c)

    fun save(c: Context, home: Float, offPeak: Float, fuel: Float, fuelPer100: Float) {
        prefs(c).edit()
            .putFloat("kwh", home)
            .putFloat("kwh_hc", offPeak)
            .putFloat("essence_prix", fuel)
            .putFloat("essence_conso", fuelPer100)
            .apply()
    }

    /**
     * Prix moyen d'un kWh mis dans la batterie : moyenne des 10 dernières recharges
     * (maison et bornes), sinon le prix de la maison. Pertes de recharge comprises.
     */
    fun pricePerBatteryKwh(c: Context): Float {
        // Relu seulement quand les recharges ou les prix changent (appelé pour chaque trajet affiché).
        val f = ChargeLog.file(c)
        val key = (f.lastModified() + 31 * f.length()) to prefs(c).all.hashCode()
        cache?.let { (k, v) -> if (k == key) return v }
        return computePrice(c).also { cache = key to it }
    }

    private var cache: Pair<Pair<Long, Int>, Float>? = null

    private fun computePrice(c: Context): Float {
        val recent = ChargeLog.charges(c).take(10)
        val kwh = recent.sumOf { it.kwhAdded.toDouble() }
        val price = if (kwh > 0) (recent.sumOf { (it.kwhAdded * it.pricePerKwh).toDouble() } / kwh).toFloat()
        else defaultChargePrice(c)
        return price / CHARGE_EFFICIENCY
    }

    /** Coût d'un trajet en euros (null si la batterie n'a pas été lue). */
    fun tripCost(c: Context, t: Trip): Float? = TripStats.kwh(t)?.let { it * pricePerBatteryKwh(c) }
}

/** Une recharge détectée : la batterie a remonté entre deux lectures. */
data class Charge(
    val id: String,
    /** Dernière lecture avant la recharge (la recharge a commencé après). */
    val start: Long,
    /** Première lecture après (ou pendant) la recharge. */
    val end: Long,
    val pctFrom: Float,
    val pctTo: Float,
    /** Prix payé par kWh sorti de la prise ou de la borne. */
    val pricePerKwh: Float,
    val place: String,
    val lat: Double?,
    val lon: Double?,
    val vehicleId: String = VehicleStore.DEFAULT_ID,
) {
    /** kWh entrés dans la batterie (selon la capacité de la batterie du véhicule). */
    val kwhAdded: Float get() =
        ((pctTo - pctFrom) / 100f * VehicleStore.get(vehicleId).batteryKwh).coerceAtLeast(0f)
    /** kWh payés (pertes de recharge comprises). */
    val kwhPaid: Float get() = kwhAdded / PriceSettings.CHARGE_EFFICIENCY
    val cost: Float get() = kwhPaid * pricePerKwh
}

/**
 * Journal des recharges, déduit des lectures de la batterie dans la voiture :
 * si le % remonte voiture arrêtée, c'est une recharge.
 */
object ChargeLog {
    private const val PREFS = "recharges"
    private const val HEADER = "id;debut;fin;batterie_debut_pct;batterie_fin_pct;kwh_batterie;prix_kwh;cout_eur;lieu;latitude;longitude;vehicule"
    /** Hausse minimale pour compter une nouvelle recharge (évite les petites variations de lecture). */
    private const val MIN_RISE = 1.5f
    /** Une hausse qui suit une recharge dans ce délai la prolonge (recharge suivie en direct). */
    private const val MERGE_MS = 3 * 3600_000L
    /** Distance sous laquelle on reprend le lieu et le prix d'une recharge précédente. */
    private const val SAME_PLACE_M = 300f

    private val idFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE)
    private val listeners = mutableSetOf<() -> Unit>()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun file(c: Context) = File(TripRecorder.dir(c), "recharges.csv")

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** Appelé à chaque lecture de la batterie dans la voiture. */
    @Synchronized
    fun onBattery(c: Context, pct: Float) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        // Une référence par véhicule : changer de voiture ne doit pas ressembler à une recharge.
        val vid = VehicleStore.currentId
        val kPct = if (vid == VehicleStore.DEFAULT_ID) "pct" else "pct_$vid"
        val kTime = if (vid == VehicleStore.DEFAULT_ID) "heure" else "heure_$vid"
        if (!p.contains(kPct)) {
            p.edit().putFloat(kPct, pct).putLong(kTime, now).apply()
            return
        }
        val base = p.getFloat(kPct, pct)
        val baseTime = p.getLong(kTime, now)
        // En roulant, le % peut remonter un peu (freinage régénératif) : ce n'est pas une recharge.
        // Si la lecture précédente est ancienne (session précédente), la hausse vient d'une recharge.
        val moving = TripRecorder.isActive && now - TripRecorder.lastMovingAt < 2 * 60_000L &&
            now - baseTime < 15 * 60_000L
        if (moving || pct <= base) {
            p.edit().putFloat(kPct, pct).putLong(kTime, now).apply()
            return
        }
        val list = charges(c).toMutableList()
        val lastIndex = list.indexOfFirst { it.vehicleId == vid }
        val last = list.getOrNull(lastIndex)
        if (last != null && now - last.end < MERGE_MS && kotlin.math.abs(last.pctTo - base) < 0.05f) {
            // La recharge continue : on la prolonge.
            list[lastIndex] = last.copy(end = now, pctTo = pct)
        } else if (pct - base >= MIN_RISE) {
            list.add(0, newCharge(c, list, baseTime, now, base, pct).copy(vehicleId = vid))
        } else {
            // Petite hausse : on garde la référence, la suite dira si c'est une recharge.
            return
        }
        p.edit().putFloat(kPct, pct).putLong(kTime, now).apply()
        write(c, list)
        notifyListeners()
    }

    private fun newCharge(c: Context, previous: List<Charge>, start: Long, end: Long, from: Float, to: Float): Charge {
        val loc = TripRecorder.lastKnownPosition(c)
        // Même endroit qu'une recharge précédente : on reprend son nom et son prix.
        val same = loc?.let { (lat, lon) ->
            previous.firstOrNull { ch ->
                ch.place.isNotEmpty() && ch.lat != null && ch.lon != null &&
                    distance(lat, lon, ch.lat, ch.lon) < SAME_PLACE_M
            }
        }
        return Charge(
            idFormat.format(Date(end)), start, end, from, to,
            same?.pricePerKwh ?: PriceSettings.defaultChargePrice(c),
            same?.place ?: "",
            loc?.first, loc?.second,
        )
    }

    /** Modifie le lieu et le prix d'une recharge (depuis le téléphone). */
    @Synchronized
    fun update(c: Context, id: String, place: String, price: Float) {
        val list = charges(c).map { if (it.id == id) it.copy(place = place.replace(";", ","), pricePerKwh = price) else it }
        write(c, list)
        notifyListeners()
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        write(c, charges(c).filter { it.id != id })
        notifyListeners()
    }

    /** Recharges, de la plus récente à la plus ancienne. */
    fun charges(c: Context): List<Charge> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { line ->
            val x = line.split(";")
            if (x.size < 11) return@mapNotNull null
            runCatching {
                Charge(
                    x[0], TripRecorder.parseTime(x[1])!!.time, TripRecorder.parseTime(x[2])!!.time,
                    x[3].toFloat(), x[4].toFloat(), x[6].toFloat(), x[8],
                    x[9].toDoubleOrNull(), x[10].toDoubleOrNull(),
                    x.getOrNull(11)?.ifBlank { null } ?: VehicleStore.DEFAULT_ID,
                )
            }.getOrNull()
        }.reversed()
    }

    private fun write(c: Context, newestFirst: List<Charge>) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)
        val sb = StringBuilder(HEADER).append('\n')
        newestFirst.reversed().forEach { ch ->
            sb.append(
                listOf(
                    ch.id, date.format(Date(ch.start)), date.format(Date(ch.end)),
                    "%.1f".format(Locale.US, ch.pctFrom), "%.1f".format(Locale.US, ch.pctTo),
                    "%.2f".format(Locale.US, ch.kwhAdded), "%.4f".format(Locale.US, ch.pricePerKwh),
                    "%.2f".format(Locale.US, ch.cost), ch.place,
                    ch.lat?.let { "%.6f".format(Locale.US, it) } ?: "",
                    ch.lon?.let { "%.6f".format(Locale.US, it) } ?: "",
                    ch.vehicleId,
                ).joinToString(";")
            ).append('\n')
        }
        file(c).writeText(sb.toString())
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0]
    }
}
