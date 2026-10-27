package fr.brice.scenictrajets

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.location.Location
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dernières valeurs lues dans la voiture (mises à jour par l'écran Android Auto). */
object CarSnapshot {
    @Volatile var batteryPercent: Float? = null
    @Volatile var rangeKm: Float? = null
    /** Compteur kilométrique (le Scénic l'envoie déjà en km). */
    @Volatile var odometerKm: Float? = null
}

/** Un trajet terminé, tel qu'enregistré dans trajets.csv. */
data class Trip(
    val id: String,
    val start: Long,
    val end: Long,
    val distanceKm: Double,
    val batteryStart: Float?,
    val batteryEnd: Float?,
    val rangeStart: Float?,
    val rangeEnd: Float?,
    val odometerStart: Float?,
    val odometerEnd: Float?,
) {
    val durationMin: Long get() = (end - start) / 60_000
}

/**
 * Enregistre les trajets : un fichier CSV de points GPS par trajet,
 * et une ligne de résumé par trajet dans trajets.csv.
 */
object TripRecorder {

    private const val SUMMARY_HEADER =
        "id;debut;fin;duree_min;distance_km;batterie_debut_pct;batterie_fin_pct;" +
            "autonomie_debut_km;autonomie_fin_km;compteur_debut_km;compteur_fin_km"
    private const val POINTS_HEADER =
        "heure;latitude;longitude;vitesse_kmh;precision_m;batterie_pct;autonomie_km;altitude_m"

    private val idFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE)

    @Volatile var isActive = false
        private set
    /** Trajet démarré automatiquement (et non avec le bouton). */
    @Volatile var isAuto = false
        private set
    @Volatile var distanceKm = 0.0
        private set
    var startTime = 0L
        private set

    private var tripId = ""
    private var lastLocation: Location? = null
    private var batteryStart: Float? = null
    private var rangeStart: Float? = null
    private var odometerStart: Float? = null
    private val listeners = mutableSetOf<() -> Unit>()

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun dir(context: Context) = File(context.filesDir, "trajets").apply { mkdirs() }
    fun summaryFile(context: Context) = File(dir(context), "trajets.csv")
    fun pointsFile(context: Context, id: String) = File(dir(context), "trajet_$id.csv")

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** Démarre un trajet. Appelé par TripService (automatiquement ou depuis le bouton). */
    @Synchronized
    fun begin(context: Context, auto: Boolean) {
        if (isActive) return
        isAuto = auto
        startTime = System.currentTimeMillis()
        tripId = idFormat.format(Date(startTime))
        distanceKm = 0.0
        lastLocation = null
        batteryStart = CarSnapshot.batteryPercent
        rangeStart = CarSnapshot.rangeKm
        odometerStart = CarSnapshot.odometerKm
        pointsFile(context, tripId).writeText(POINTS_HEADER + "\n")
        isActive = true
        notifyListeners()
    }

    /** Termine le trajet en cours et l'ajoute à l'historique (les trajets de moins de 200 m sont ignorés). */
    @Synchronized
    fun finish(context: Context) {
        if (!isActive) return
        isActive = false
        if (distanceKm < 0.2) {
            pointsFile(context, tripId).delete()
            notifyListeners()
            return
        }
        val summary = summaryFile(context)
        if (!summary.exists()) summary.writeText(SUMMARY_HEADER + "\n")
        val trip = Trip(
            tripId, startTime, System.currentTimeMillis(), distanceKm,
            batteryStart, CarSnapshot.batteryPercent, rangeStart, CarSnapshot.rangeKm,
            odometerStart, CarSnapshot.odometerKm,
        )
        summary.appendText(toCsv(trip) + "\n")
        runCatching { writeGpx(context, tripId) }
        notifyListeners()
    }

    /** Abandonne le trajet en cours sans l'enregistrer (par exemple : pas dans le Scénic). */
    @Synchronized
    fun discard(context: Context) {
        if (!isActive) return
        isActive = false
        pointsFile(context, tripId).delete()
        notifyListeners()
    }

    fun gpxFile(context: Context, id: String) = File(dir(context), "trajet_$id.gpx")

    /** Convertit les points d'un trajet en GPX, importable dans Dawarich. */
    fun writeGpx(context: Context, id: String) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Scenic Trajets\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>Trajet $id</name><type>car</type><trkseg>\n")
        pointsFile(context, id).readLines().drop(1).forEach { line ->
            val c = line.split(";")
            if (c.size < 3) return@forEach
            val time = runCatching { dateFormat.parse(c[0]) }.getOrNull() ?: return@forEach
            sb.append("    <trkpt lat=\"${c[1]}\" lon=\"${c[2]}\">")
            if (c.size > 7 && c[7].isNotEmpty()) sb.append("<ele>${c[7]}</ele>")
            sb.append("<time>${iso.format(time)}</time></trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        gpxFile(context, id).writeText(sb.toString())
    }

    /** Appelé par TripService à chaque nouvelle position GPS. */
    @Synchronized
    fun onLocation(context: Context, loc: Location) {
        if (!isActive) return
        // On ignore les positions trop imprécises pour le calcul de distance.
        if (loc.accuracy <= 50f) {
            lastLocation?.let { distanceKm += it.distanceTo(loc) / 1000.0 }
            lastLocation = loc
        }
        val line = listOf(
            dateFormat.format(Date(loc.time)),
            "%.6f".format(Locale.US, loc.latitude),
            "%.6f".format(Locale.US, loc.longitude),
            if (loc.hasSpeed()) "%.1f".format(Locale.US, loc.speed * 3.6f) else "",
            "%.0f".format(Locale.US, loc.accuracy),
            fmt(CarSnapshot.batteryPercent),
            fmt(CarSnapshot.rangeKm),
            if (loc.hasAltitude()) "%.0f".format(Locale.US, loc.altitude) else "",
        ).joinToString(";")
        pointsFile(context, tripId).appendText(line + "\n")
        notifyListeners()
    }

    /** Liste des trajets terminés, du plus récent au plus ancien. */
    fun trips(context: Context): List<Trip> {
        val f = summaryFile(context)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).mapNotNull { line ->
            val c = line.split(";")
            if (c.size < 11) return@mapNotNull null
            runCatching {
                Trip(
                    c[0], dateFormat.parse(c[1])!!.time, dateFormat.parse(c[2])!!.time,
                    c[4].toDouble(), c[5].toFloatOrNull(), c[6].toFloatOrNull(),
                    c[7].toFloatOrNull(), c[8].toFloatOrNull(), c[9].toFloatOrNull(), c[10].toFloatOrNull(),
                )
            }.getOrNull()
        }.reversed()
    }

    private fun toCsv(t: Trip) = listOf(
        t.id, dateFormat.format(Date(t.start)), dateFormat.format(Date(t.end)), t.durationMin.toString(),
        "%.2f".format(Locale.US, t.distanceKm), fmt(t.batteryStart), fmt(t.batteryEnd),
        fmt(t.rangeStart), fmt(t.rangeEnd), fmt(t.odometerStart), fmt(t.odometerEnd),
    ).joinToString(";")

    private fun fmt(v: Float?) = v?.let { "%.1f".format(Locale.US, it) } ?: ""
}
