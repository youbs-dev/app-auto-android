package fr.brice.scenictrajets

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Température extérieure de chaque trajet, demandée au service gratuit Open-Meteo
 * (sans clé) pour l'heure et le lieu du trajet.
 */
object TripWeather {
    private const val TAG = "ScenicMeteo"

    /** Température au départ du trajet en cours (null tant qu'elle n'est pas connue). */
    @Volatile var liveTemp: Float? = null
        private set
    @Volatile private var liveFor = 0L

    @Volatile private var cache: Pair<Long, Map<String, Float>>? = null

    private fun file(c: Context) = File(TripRecorder.dir(c), "meteo.csv")

    /** Températures connues, par identifiant de trajet. */
    fun all(c: Context): Map<String, Float> {
        val f = file(c)
        if (!f.exists()) return emptyMap()
        val stamp = f.lastModified() + f.length()
        cache?.let { (s, map) -> if (s == stamp) return map }
        val map = f.readLines().mapNotNull { line ->
            val x = line.split(";")
            val t = x.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            x[0] to t
        }.toMap()
        cache = stamp to map
        return map
    }

    fun of(c: Context, id: String): Float? = all(c)[id]

    fun label(v: Float) = String.format(Locale.FRANCE, "%.0f °C", v)

    /** Appelé à la première position GPS d'un trajet : température actuelle au départ. */
    fun onTripStart(startTime: Long, lat: Double, lon: Double) {
        if (liveFor == startTime) return
        liveFor = startTime
        liveTemp = null
        Thread {
            runCatching {
                val json = get(
                    "https://api.open-meteo.com/v1/forecast?latitude=${coord(lat)}&longitude=${coord(lon)}" +
                        "&current=temperature_2m&timezone=UTC"
                )
                val t = json.getJSONObject("current").getDouble("temperature_2m").toFloat()
                if (liveFor == startTime) liveTemp = t
            }.onFailure { Log.w(TAG, "Température actuelle indisponible", it) }
        }.start()
    }

    /** Cherche en arrière-plan la température des trajets qui n'en ont pas encore (il faut Internet). */
    fun resolveMissingSoon(c: Context) {
        val app = c.applicationContext
        Thread {
            runCatching { resolveMissing(app) }.onFailure { Log.w(TAG, "Météo : échec", it) }
        }.start()
    }

    @Synchronized
    private fun resolveMissing(c: Context) {
        val known = all(c)
        // Open-Meteo garde les 92 derniers jours dans son service de prévisions.
        val limit = System.currentTimeMillis() - 90L * 24 * 3600_000
        for (t in TripRecorder.trips(c).take(40)) {
            if (known.containsKey(t.id) || t.start < limit) continue
            val p = TripPlaces.points(c, t.id).let { it.getOrNull(it.size / 2) } ?: continue
            val temp = temperatureAt(p.lat, p.lon, (t.start + t.end) / 2) ?: continue
            file(c).appendText(t.id + ";" + "%.1f".format(Locale.US, temp) + "\n")
            TripRecorder.changed()
        }
    }

    /** Température à l'heure la plus proche de [time], au point donné. */
    private fun temperatureAt(lat: Double, lon: Double, time: Long): Float? {
        val daysAgo = ((System.currentTimeMillis() - time) / (24 * 3600_000L)).toInt() + 1
        val json = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${coord(lat)}&longitude=${coord(lon)}" +
                "&hourly=temperature_2m&past_days=${daysAgo.coerceIn(1, 92)}&forecast_days=1&timezone=UTC"
        )
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        var best: Float? = null
        var bestGap = Long.MAX_VALUE
        for (i in 0 until times.length()) {
            if (temps.isNull(i)) continue
            val at = runCatching { fmt.parse(times.getString(i))!!.time }.getOrNull() ?: continue
            val gap = kotlin.math.abs(at - time)
            if (gap < bestGap) { bestGap = gap; best = temps.getDouble(i).toFloat() }
        }
        return best?.takeIf { bestGap <= 2 * 3600_000L }
    }

    private fun coord(v: Double) = "%.3f".format(Locale.US, v)

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode != 200) throw Exception("Open-Meteo : erreur ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    // --- Conso selon la température ------------------------------------------------------------

    class Band(val label: String, val trips: Int, val km: Double, val kwhPer100: Float?)

    private val BANDS = listOf(
        Triple(Float.NEGATIVE_INFINITY, 5f, "Moins de 5 °C"),
        Triple(5f, 15f, "5 à 15 °C"),
        Triple(15f, 25f, "15 à 25 °C"),
        Triple(25f, Float.POSITIVE_INFINITY, "Plus de 25 °C"),
    )

    /** Consommation moyenne par tranche de température (trajets où batterie et météo sont connues). */
    fun bands(c: Context, trips: List<Trip>): List<Band> {
        val temps = all(c)
        return BANDS.mapNotNull { (lo, hi, label) ->
            val list = trips.filter { t -> temps[t.id]?.let { it >= lo && it < hi } == true && TripStats.kwh(t) != null }
            if (list.isEmpty()) return@mapNotNull null
            val km = list.sumOf { it.distanceKm }
            val kwh = list.mapNotNull { TripStats.kwh(it) }.sum()
            Band(label, list.size, km, if (km >= 10) (kwh / km * 100).toFloat() else null)
        }
    }
}
