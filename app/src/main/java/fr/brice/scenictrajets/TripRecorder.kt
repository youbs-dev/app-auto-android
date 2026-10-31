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
    /** Véhicule du trajet (voir [VehicleStore]). */
    val vehicleId: String = VehicleStore.DEFAULT_ID,
) {
    val vehicle: Vehicle get() = VehicleStore.get(vehicleId)
    val durationMin: Long get() = (end - start) / 60_000
}

/**
 * Enregistre les trajets : un fichier CSV de points GPS par trajet,
 * et une ligne de résumé par trajet dans trajets.csv.
 */
object TripRecorder {

    private const val SUMMARY_HEADER =
        "id;debut;fin;duree_min;distance_km;batterie_debut_pct;batterie_fin_pct;" +
            "autonomie_debut_km;autonomie_fin_km;compteur_debut_km;compteur_fin_km;vehicule"
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

    /** Vitesse GPS actuelle et maximale du trajet en cours, en km/h. */
    @Volatile var speedKmh: Float? = null
        private set
    @Volatile var maxSpeedKmh = 0f
        private set
    /** Dernière fois que la voiture roulait (mis à jour par TripService). */
    @Volatile var lastMovingAt = 0L

    private var tripId = ""
    /** Dernière position GPS connue, même hors trajet (pour situer les recharges). */
    @Volatile private var lastFix: Location? = null
    private var lastLocation: Location? = null
    private var batteryStart: Float? = null
    /** Batterie au départ du trajet en cours (pour afficher ce qui a été utilisé). */
    val batteryAtStart: Float? get() = batteryStart
    private var rangeStart: Float? = null
    private var odometerStart: Float? = null
    private val listeners = mutableSetOf<() -> Unit>()

    /** Un nouveau trajet qui commence moins de 5 min après le précédent peut le continuer. */
    private const val RESUME_GAP_MS = 5 * 60_000L
    /** La proposition de reprise reste affichée pendant les 10 premières minutes du nouveau trajet. */
    private const val RESUME_OFFER_MS = 10 * 60_000L
    @Volatile private var resumeCandidate: Trip? = null

    /** Trajet précédent que l'on peut reprendre, ou null. */
    val resumeOffer: Trip?
        get() = resumeCandidate?.takeIf { isActive && System.currentTimeMillis() - startTime < RESUME_OFFER_MS }

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun dir(context: Context) = File(context.filesDir, "trajets").apply { mkdirs() }
    fun summaryFile(context: Context) = File(dir(context), "trajets.csv")
    fun pointsFile(context: Context, id: String) = File(dir(context), "trajet_$id.csv")

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    /** Prévient les écrans qu'une information d'un trajet a changé (lieux retrouvés, par exemple). */
    fun changed() = notifyListeners()

    private fun notifyListeners() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** Démarre un trajet. Appelé par TripService (automatiquement ou depuis le bouton). */
    @Synchronized
    fun begin(context: Context, auto: Boolean) {
        if (isActive) return
        isAuto = auto
        startTime = System.currentTimeMillis()
        tripId = idFormat.format(Date(startTime))
        distanceKm = 0.0
        speedKmh = null
        maxSpeedKmh = 0f
        lastLocation = null
        batteryStart = CarSnapshot.batteryPercent
        rangeStart = CarSnapshot.rangeKm
        odometerStart = CarSnapshot.odometerKm
        pointsFile(context, tripId).writeText(POINTS_HEADER + "\n")
        resumeCandidate = runCatching { trips(context).firstOrNull() }.getOrNull()?.takeIf {
            startTime - it.end in 0..RESUME_GAP_MS && it.vehicleId == VehicleStore.currentId
        }
        isActive = true
        notifyListeners()
    }

    /**
     * Appelé à chaque nouvelle lecture de la voiture. Si le trajet a démarré avant que
     * la voiture n'envoie ses valeurs, on prend la première lecture comme point de départ.
     */
    @Synchronized
    fun onCarData() {
        if (!isActive) return
        if (batteryStart == null) batteryStart = CarSnapshot.batteryPercent
        if (rangeStart == null) rangeStart = CarSnapshot.rangeKm
        if (odometerStart == null) odometerStart = CarSnapshot.odometerKm
    }

    /** Dernière position connue (latitude, longitude), gardée aussi après la fin du trajet. */
    fun lastKnownPosition(context: Context): Pair<Double, Double>? {
        lastFix?.let { return it.latitude to it.longitude }
        val p = context.getSharedPreferences("position", Context.MODE_PRIVATE)
        if (!p.contains("lat")) return null
        return p.getFloat("lat", 0f).toDouble() to p.getFloat("lon", 0f).toDouble()
    }

    /** Termine le trajet en cours et l'ajoute à l'historique (les trajets de moins de 200 m sont ignorés). */
    @Synchronized
    fun finish(context: Context) {
        if (!isActive) return
        isActive = false
        resumeCandidate = null
        lastFix?.let {
            context.getSharedPreferences("position", Context.MODE_PRIVATE).edit()
                .putFloat("lat", it.latitude.toFloat()).putFloat("lon", it.longitude.toFloat()).apply()
        }
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
            // Le véhicule est connu dès qu'Android Auto a donné le modèle de la voiture.
            VehicleStore.currentId,
        )
        summary.appendText(toCsv(trip) + "\n")
        runCatching { writeGpx(context, tripId) }
        // Copie de sauvegarde dans Téléchargements, puis envoi à Dawarich dès qu'Internet est là.
        LocalBackup.save(context, gpxFile(context, tripId))
        LocalBackup.save(context, pointsFile(context, tripId))
        runCatching { SyncWorker.runSoon(context) }
        TripPlaces.resolveMissingSoon(context)
        Routes.computeMissingSoon(context)
        TripWeather.resolveMissingSoon(context)
        notifyListeners()
    }

    /**
     * Continue le trajet précédent au lieu d'en créer un nouveau : les points déjà enregistrés
     * sont ajoutés à ceux du trajet précédent, qui retrouve son heure et sa batterie de départ.
     */
    @Synchronized
    fun resumePrevious(context: Context): Boolean {
        val prev = resumeOffer ?: return false
        resumeCandidate = null
        val current = pointsFile(context, tripId)
        val target = pointsFile(context, prev.id)
        val newPoints = current.takeIf { it.exists() }?.readLines()?.drop(1)?.filter { it.isNotBlank() }.orEmpty()
        if (!target.exists()) target.writeText(POINTS_HEADER + "\n")
        if (newPoints.isNotEmpty()) target.appendText(newPoints.joinToString("\n") + "\n")
        current.delete()

        // Le trajet précédent sort de l'historique : il y reviendra, complet, à la fin.
        val summary = summaryFile(context)
        if (summary.exists()) {
            val kept = trips(context).reversed().filter { it.id != prev.id }
            summary.writeText(SUMMARY_HEADER + "\n" + kept.joinToString("") { toCsv(it) + "\n" })
        }
        // Ce qui dépend de la fin du trajet sera recalculé : GPX, lieu d'arrivée, extrémités, envoi Dawarich.
        gpxFile(context, prev.id).delete()
        forgetId(File(dir(context), "lieux.csv"), prev.id)
        forgetId(File(dir(context), "extremites.csv"), prev.id)
        sentFile(context).takeIf { it.exists() }?.let { f ->
            f.writeText(f.readLines().filter { it.isNotBlank() && it != prev.id }.joinToString("") { it + "\n" })
        }

        tripId = prev.id
        startTime = prev.start
        distanceKm += prev.distanceKm
        batteryStart = prev.batteryStart ?: batteryStart
        rangeStart = prev.rangeStart ?: rangeStart
        odometerStart = prev.odometerStart ?: odometerStart
        notifyListeners()
        return true
    }

    /** Retire d'un fichier « id;… » les lignes d'un trajet. */
    private fun forgetId(f: File, id: String) {
        if (!f.exists()) return
        f.writeText(f.readLines().filter { it.isNotBlank() && !it.startsWith("$id;") }.joinToString("") { it + "\n" })
    }

    /** Abandonne le trajet en cours sans l'enregistrer (par exemple : pas dans le Scénic). */
    @Synchronized
    fun discard(context: Context) {
        if (!isActive) return
        isActive = false
        pointsFile(context, tripId).delete()
        notifyListeners()
    }

    /** Nouveau format à chaque appel : la lecture peut se faire depuis la tâche d'envoi, en parallèle. */
    fun parseTime(s: String): Date? = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).parse(s)

    private fun sentFile(context: Context) = File(dir(context), "envoyes_dawarich.txt")

    fun isSent(context: Context, id: String) =
        sentFile(context).let { it.exists() && it.readLines().contains(id) }

    @Synchronized
    fun markSent(context: Context, id: String) {
        sentFile(context).appendText(id + "\n")
        notifyListeners()
    }

    private fun errorFile(context: Context) = File(dir(context), "derniere_erreur.txt")

    fun lastError(context: Context): String? =
        errorFile(context).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun setLastError(context: Context, message: String?) {
        if (message == null) errorFile(context).delete() else errorFile(context).writeText(message)
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
            val time = runCatching { parseTime(c[0]) }.getOrNull() ?: return@forEach
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
        if (loc.accuracy <= 50f) lastFix = loc
        if (!isActive) return
        speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else null
        speedKmh?.let { if (loc.accuracy <= 30f && it > maxSpeedKmh) maxSpeedKmh = it }
        // On ignore les positions trop imprécises pour le calcul de distance.
        if (loc.accuracy <= 50f) {
            if (lastLocation == null) {
                TripPlaces.onTripStart(context, startTime, loc.latitude, loc.longitude)
                TripWeather.onTripStart(startTime, loc.latitude, loc.longitude)
            }
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
                    c[0], parseTime(c[1])!!.time, parseTime(c[2])!!.time,
                    c[4].toDouble(), c[5].toFloatOrNull(), c[6].toFloatOrNull(),
                    c[7].toFloatOrNull(), c[8].toFloatOrNull(), c[9].toFloatOrNull(), c[10].toFloatOrNull(),
                    c.getOrNull(11)?.ifBlank { null } ?: VehicleStore.DEFAULT_ID,
                )
            }.getOrNull()
        }.reversed()
    }

    private fun toCsv(t: Trip) = listOf(
        t.id, dateFormat.format(Date(t.start)), dateFormat.format(Date(t.end)), t.durationMin.toString(),
        "%.2f".format(Locale.US, t.distanceKm), fmt(t.batteryStart), fmt(t.batteryEnd),
        fmt(t.rangeStart), fmt(t.rangeEnd), fmt(t.odometerStart), fmt(t.odometerEnd), t.vehicleId,
    ).joinToString(";")

    /** Ajoute la colonne « vehicule » aux anciens historiques : les trajets d'avant sont ceux du Scénic. */
    @Synchronized
    fun migrateVehicleColumn(context: Context) {
        val f = summaryFile(context)
        if (!f.exists()) return
        val lines = f.readLines()
        if (lines.isEmpty() || lines[0].endsWith(";vehicule")) return
        val out = StringBuilder(SUMMARY_HEADER).append('\n')
        lines.drop(1).filter { it.isNotBlank() }.forEach { l ->
            out.append(if (l.split(";").size == 11) l + ";" + VehicleStore.DEFAULT_ID else l).append('\n')
        }
        f.writeText(out.toString())
    }

    /** Change le véhicule d'un trajet (correction à la main depuis le téléphone). */
    @Synchronized
    fun setVehicle(context: Context, id: String, vehicleId: String) {
        val f = summaryFile(context)
        if (!f.exists()) return
        val updated = trips(context).reversed().map { if (it.id == id) it.copy(vehicleId = vehicleId) else it }
        f.writeText(SUMMARY_HEADER + "\n" + updated.joinToString("") { toCsv(it) + "\n" })
        notifyListeners()
    }

    private fun fmt(v: Float?) = v?.let { "%.1f".format(Locale.US, it) } ?: ""
}
