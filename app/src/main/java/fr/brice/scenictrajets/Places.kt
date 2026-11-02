package fr.brice.scenictrajets

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import java.io.File
import java.util.Locale

/** Un point GPS lu dans le fichier d'un trajet. */
data class TrackPoint(val time: Long, val lat: Double, val lon: Double, val speedKmh: Float?)

/** Lieux de départ et d'arrivée des trajets, retrouvés à partir des coordonnées GPS. */
object TripPlaces {
    private const val TAG = "ScenicLieux"

    data class Places(val start: String, val end: String)

    /** Lieu de départ du trajet en cours (null tant qu'il n'est pas connu). */
    @Volatile var liveStart: String? = null
        private set
    @Volatile private var liveStartFor = 0L

    @Volatile private var cache: Pair<Long, Map<String, Places>>? = null

    private fun file(c: Context) = File(TripRecorder.dir(c), "lieux.csv")

    /** Lieux connus, par identifiant de trajet. */
    fun all(c: Context): Map<String, Places> {
        val f = file(c)
        if (!f.exists()) return emptyMap()
        cache?.let { (stamp, map) -> if (stamp == f.lastModified() + f.length()) return map }
        val map = f.readLines().mapNotNull { line ->
            val x = line.split(";")
            if (x.size < 3) null else x[0] to Places(x[1], x[2])
        }.toMap()
        cache = (f.lastModified() + f.length()) to map
        return map
    }

    fun of(c: Context, id: String): Places? = all(c)[id]

    /** « Lyon 3e → Villeurbanne », ou null si les lieux ne sont pas encore connus. */
    fun label(c: Context, id: String): String? = of(c, id)?.let { p ->
        if (p.start == p.end) "Boucle autour de ${p.start}" else "${p.start} → ${p.end}"
    }

    /** Points GPS d'un trajet (vide si le fichier a été supprimé après la durée de conservation). */
    fun points(c: Context, id: String): List<TrackPoint> {
        val f = TripRecorder.pointsFile(c, id)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { line ->
            val x = line.split(";")
            if (x.size < 5) return@mapNotNull null
            val acc = x[4].toFloatOrNull() ?: 0f
            if (acc > 50f) return@mapNotNull null
            val time = TripRecorder.parseTime(x[0])?.time ?: return@mapNotNull null
            val lat = x[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = x[2].toDoubleOrNull() ?: return@mapNotNull null
            TrackPoint(time, lat, lon, x[3].toFloatOrNull())
        }
    }

    /** Cherche en arrière-plan les lieux des trajets qui n'en ont pas encore (il faut Internet). */
    fun resolveMissingSoon(c: Context) {
        val app = c.applicationContext
        Thread {
            runCatching { resolveMissing(app) }.onFailure { Log.w(TAG, "Lieux : échec", it) }
        }.start()
    }

    @Synchronized
    private fun resolveMissing(c: Context) {
        if (!Geocoder.isPresent()) return
        val known = all(c)
        for (t in TripRecorder.trips(c).take(30)) {
            if (known.containsKey(t.id)) continue
            val pts = points(c, t.id)
            if (pts.isEmpty()) continue
            val start = name(c, pts.first().lat, pts.first().lon) ?: continue
            val end = name(c, pts.last().lat, pts.last().lon) ?: continue
            file(c).appendText("${t.id};$start;$end\n")
            TripRecorder.changed()
        }
    }

    /** Appelé à la première position GPS d'un trajet : cherche le lieu de départ. */
    fun onTripStart(c: Context, startTime: Long, lat: Double, lon: Double) {
        if (liveStartFor == startTime) return
        liveStartFor = startTime
        liveStart = null
        val app = c.applicationContext
        Thread { name(app, lat, lon)?.let { if (liveStartFor == startTime) liveStart = it } }.start()
    }

    /** Nom court d'un lieu : « 12 rue Victor Hugo, Lyon », ou la ville seule. */
    @Suppress("DEPRECATION")
    fun name(c: Context, lat: Double, lon: Double): String? = try {
        val a = Geocoder(c, Locale.FRANCE).getFromLocation(lat, lon, 1)?.firstOrNull()
        a?.let {
            val street = listOfNotNull(it.subThoroughfare, it.thoroughfare).joinToString(" ").ifBlank { null }
            val town = it.locality ?: it.subAdminArea
            listOfNotNull(street, town).joinToString(", ").ifBlank { null }
        }?.replace(";", ",")
    } catch (e: Exception) {
        Log.w(TAG, "Géocodage impossible", e)
        null
    }

    /** Position actuelle du téléphone : GPS du trajet, sinon dernière position connue du système. */
    @SuppressLint("MissingPermission")
    fun currentPosition(c: Context): Pair<Double, Double>? {
        if (TripRecorder.isActive) TripRecorder.lastKnownPosition(c)?.let { return it }
        val granted = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val lm = c.getSystemService(LocationManager::class.java)
        val best: Location? = if (!granted || lm == null) null else
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        return best?.let { it.latitude to it.longitude } ?: TripRecorder.lastKnownPosition(c)
    }
}
