package fr.brice.scenictrajets

import android.content.Context
import android.location.Location
import java.io.File
import java.util.Calendar
import java.util.Locale

/** Point de départ et d'arrivée d'un trajet. */
data class Ends(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double)

/**
 * Parcours habituels : domicile et travail (trouvés tout seuls ou réglés à la main),
 * et comparaison de chaque trajet avec la moyenne des trajets sur le même parcours.
 */
object Routes {
    /** Deux extrémités à moins de cette distance sont le même lieu. */
    private const val SAME_PLACE_M = 300f
    private const val PREFS = "parcours"

    enum class Kind(val label: String) { HOME_WORK("Domicile → travail"), WORK_HOME("Travail → domicile"), OTHER("") }

    /** Ce que l'on sait d'un trajet par rapport à ses semblables. */
    class Compare(
        val kind: Kind,
        /** Nombre d'autres trajets sur le même parcours. */
        val similar: Int,
        val avgMin: Float?,
        val avgKwh100: Float?,
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun file(c: Context) = File(TripRecorder.dir(c), "extremites.csv")

    @Volatile private var cache: Pair<Long, Map<String, Ends>>? = null

    // --- Extrémités des trajets (gardées même après la suppression des points) ---------------------

    fun ends(c: Context): Map<String, Ends> {
        val f = file(c)
        if (!f.exists()) return emptyMap()
        val stamp = f.lastModified() + f.length()
        cache?.let { (s, m) -> if (s == stamp) return m }
        val map = f.readLines().mapNotNull { l ->
            val x = l.split(";")
            if (x.size < 5) return@mapNotNull null
            val v = x.drop(1).take(4).map { it.toDoubleOrNull() ?: return@mapNotNull null }
            x[0] to Ends(v[0], v[1], v[2], v[3])
        }.toMap()
        cache = stamp to map
        return map
    }

    /** Note les extrémités des trajets qui n'en ont pas encore (rapide, sans Internet). */
    @Synchronized
    fun computeMissing(c: Context) {
        val known = ends(c)
        val sb = StringBuilder()
        for (t in TripRecorder.trips(c)) {
            if (known.containsKey(t.id)) continue
            val pts = TripPlaces.points(c, t.id)
            if (pts.size < 2) continue
            sb.append(
                listOf(t.id, pts.first().lat, pts.first().lon, pts.last().lat, pts.last().lon)
                    .joinToString(";") { if (it is Double) "%.6f".format(Locale.US, it) else it.toString() }
            ).append('\n')
        }
        if (sb.isNotEmpty()) {
            file(c).appendText(sb.toString())
            TripRecorder.changed()
        }
    }

    fun computeMissingSoon(c: Context) {
        val app = c.applicationContext
        Thread { runCatching { computeMissing(app) } }.start()
    }

    // --- Domicile et travail -------------------------------------------------------------------------

    /** Lieu réglé à la main, sinon trouvé à partir des trajets. */
    fun home(c: Context): Pair<Double, Double>? = manual(c, "domicile") ?: detected(c).first
    fun work(c: Context): Pair<Double, Double>? = manual(c, "travail") ?: detected(c).second
    fun isManual(c: Context, which: String) = manual(c, which) != null

    private fun manual(c: Context, which: String): Pair<Double, Double>? {
        val p = prefs(c)
        if (!p.contains("${which}_lat")) return null
        return p.getFloat("${which}_lat", 0f).toDouble() to p.getFloat("${which}_lon", 0f).toDouble()
    }

    /** [which] : « domicile » ou « travail ». null efface le réglage (retour à la détection). */
    fun setManual(c: Context, which: String, pos: Pair<Double, Double>?) {
        val e = prefs(c).edit()
        if (pos == null) e.remove("${which}_lat").remove("${which}_lon")
        else e.putFloat("${which}_lat", pos.first.toFloat()).putFloat("${which}_lon", pos.second.toFloat())
        e.apply()
        detectedCache = null
        TripRecorder.changed()
    }

    @Volatile private var detectedCache: Pair<Int, Pair<Pair<Double, Double>?, Pair<Double, Double>?>>? = null

    /**
     * Domicile : le lieu où les trajets finissent le plus souvent le soir (après 17 h) ou commencent le matin.
     * Travail : un autre lieu où l'on arrive le matin en semaine, au moins 3 fois.
     */
    private fun detected(c: Context): Pair<Pair<Double, Double>?, Pair<Double, Double>?> {
        val trips = TripRecorder.trips(c)
        val e = ends(c)
        val key = e.size * 31 + trips.size
        detectedCache?.let { (k, v) -> if (k == key) return v }
        val clusters = mutableListOf<Cluster>()
        fun add(lat: Double, lon: Double, home: Int, work: Int) {
            val cl = clusters.firstOrNull { dist(it.lat, it.lon, lat, lon) < SAME_PLACE_M }
                ?: Cluster(lat, lon).also { clusters += it }
            cl.home += home
            cl.work += work
        }
        val cal = Calendar.getInstance()
        for (t in trips) {
            val en = e[t.id] ?: continue
            cal.timeInMillis = t.start
            val startHour = cal.get(Calendar.HOUR_OF_DAY)
            cal.timeInMillis = t.end
            val endHour = cal.get(Calendar.HOUR_OF_DAY)
            val weekday = cal.get(Calendar.DAY_OF_WEEK) !in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
            add(en.lat1, en.lon1, if (startHour in 5..10) 1 else 0, 0)
            add(en.lat2, en.lon2, if (endHour >= 17) 1 else 0, if (weekday && endHour in 6..11) 1 else 0)
        }
        val home = clusters.filter { it.home >= 2 }.maxByOrNull { it.home }
        val work = clusters.filter { it !== home && it.work >= 3 }.maxByOrNull { it.work }
        val result = home?.let { it.lat to it.lon } to work?.let { it.lat to it.lon }
        detectedCache = key to result
        return result
    }

    private class Cluster(val lat: Double, val lon: Double) { var home = 0; var work = 0 }

    // --- Comparaison --------------------------------------------------------------------------------

    fun kind(c: Context, t: Trip): Kind {
        val en = ends(c)[t.id] ?: return Kind.OTHER
        val h = home(c) ?: return Kind.OTHER
        val w = work(c) ?: return Kind.OTHER
        return when {
            near(en.lat1, en.lon1, h) && near(en.lat2, en.lon2, w) -> Kind.HOME_WORK
            near(en.lat1, en.lon1, w) && near(en.lat2, en.lon2, h) -> Kind.WORK_HOME
            else -> Kind.OTHER
        }
    }

    /** Compare un trajet à la moyenne des autres trajets du même véhicule sur le même parcours (même départ, même arrivée). */
    fun compare(c: Context, t: Trip, all: List<Trip>): Compare? {
        val e = ends(c)
        val me = e[t.id] ?: return null
        val same = all.filter { o ->
            o.id != t.id && o.vehicleId == t.vehicleId && e[o.id]?.let { oe ->
                dist(oe.lat1, oe.lon1, me.lat1, me.lon1) < SAME_PLACE_M && dist(oe.lat2, oe.lon2, me.lat2, me.lon2) < SAME_PLACE_M
            } == true
        }
        if (same.size < 2) return null
        val withConso = same.mapNotNull { o -> TripStats.kwh(o)?.let { o to it } }
        val km = withConso.sumOf { it.first.distanceKm }
        return Compare(
            kind = kind(c, t),
            similar = same.size,
            avgMin = same.map { it.durationMin.toFloat() }.average().toFloat(),
            avgKwh100 = if (withConso.size >= 2 && km >= 5) (withConso.sumOf { it.second.toDouble() } / km * 100).toFloat() else null,
        )
    }

    /** « 32 min (moyenne 28, +4 min) · 17,2 kWh/100 (moyenne 16,5) ». */
    fun compareLine(t: Trip, cmp: Compare): String {
        val parts = mutableListOf<String>()
        cmp.avgMin?.let { avg ->
            val d = t.durationMin - avg
            val sign = when { d >= 1 -> String.format(Locale.FRANCE, ", +%.0f min", d); d <= -1 -> String.format(Locale.FRANCE, ", −%.0f min", -d); else -> ", comme d'habitude" }
            parts += String.format(Locale.FRANCE, "%d min (moyenne %.0f%s)", t.durationMin, avg, sign)
        }
        val mine = TripStats.kwhPer100(t)
        if (mine != null && cmp.avgKwh100 != null) {
            parts += String.format(Locale.FRANCE, "%.1f kWh/100 (moyenne %.1f)", mine, cmp.avgKwh100)
        }
        return parts.joinToString(" · ")
    }

    private fun near(lat: Double, lon: Double, p: Pair<Double, Double>) = dist(lat, lon, p.first, p.second) < SAME_PLACE_M

    private fun dist(a: Double, b: Double, c: Double, d: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(a, b, c, d, r)
        return r[0]
    }
}
