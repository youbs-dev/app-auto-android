package fr.brice.scenictrajets

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val TAG = "ScenicSync"

/** Réglages du transfert, enregistrés sur le téléphone. */
object SyncSettings {
    private const val PREFS = "reglages"

    /** Choix proposés pour la conservation, en jours (0 = garder pour toujours). */
    val RETENTION_CHOICES = listOf(
        7 to "1 semaine", 30 to "1 mois", 60 to "2 mois", 180 to "6 mois", 365 to "1 an", 0 to "Toujours",
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun url(c: Context) = prefs(c).getString("dawarich_url", "")!!.trim().trimEnd('/')
    fun apiKey(c: Context) = prefs(c).getString("dawarich_key", "")!!.trim()
    fun autoUpload(c: Context) = prefs(c).getBoolean("dawarich_auto", true)
    fun retentionDays(c: Context) = prefs(c).getInt("retention_jours", 60)
    fun isConfigured(c: Context) = url(c).isNotEmpty() && apiKey(c).isNotEmpty()

    fun save(c: Context, url: String, key: String, auto: Boolean, retentionDays: Int) {
        prefs(c).edit()
            .putString("dawarich_url", url.trim())
            .putString("dawarich_key", key.trim())
            .putBoolean("dawarich_auto", auto)
            .putInt("retention_jours", retentionDays)
            .apply()
    }
}

/** Trajet sans point à envoyer : ce n'est pas une panne, la tâche automatique passe au suivant. */
class NothingToSend(message: String) : Exception(message)

/** Envoi des points vers Dawarich, par l'API « Overland » (POST /api/v1/overland/batches). */
object Dawarich {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Envoie un lot vide : vérifie l'adresse et la clé. Renvoie null si tout va bien, sinon l'erreur. */
    fun test(context: Context): String? = try {
        post(context, JSONArray())
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Envoie tous les points d'un trajet, par lots de 500. Renvoie le nombre de points envoyés. */
    fun uploadTrip(context: Context, id: String): Int {
        val file = TripRecorder.pointsFile(context, id)
        if (!file.exists()) throw NothingToSend("les points de ce trajet ne sont plus sur le téléphone")
        val batch = JSONArray()
        var sent = 0
        file.readLines().drop(1).forEach { line ->
            toFeature(line)?.let { batch.put(it) }
            if (batch.length() >= 500) {
                post(context, batch)
                sent += batch.length()
                while (batch.length() > 0) batch.remove(0)
            }
        }
        if (batch.length() > 0) {
            post(context, batch)
            sent += batch.length()
        }
        if (sent == 0) throw NothingToSend("aucun point GPS valide dans ce trajet")
        return sent
    }

    private fun toFeature(line: String): JSONObject? {
        val c = line.split(";")
        if (c.size < 5) return null
        val time = runCatching { TripRecorder.parseTime(c[0]) }.getOrNull() ?: return null
        val lat = c[1].toDoubleOrNull() ?: return null
        val lon = c[2].toDoubleOrNull() ?: return null
        val props = JSONObject()
            .put("timestamp", iso.format(time))
            .put("device_id", "scenic")
        c[3].toDoubleOrNull()?.let { props.put("speed", it / 3.6) }
        c[4].toDoubleOrNull()?.let { props.put("horizontal_accuracy", it) }
        c.getOrNull(7)?.toDoubleOrNull()?.let { props.put("altitude", it) }
        return JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))
            .put("properties", props)
    }

    private fun post(context: Context, locations: JSONArray) {
        val url = URL(SyncSettings.url(context) + "/api/v1/overland/batches")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // Une redirection (http → https, page de connexion d'un proxy…) transformerait l'envoi
            // en simple lecture de page, qui répond « 200 » sans rien enregistrer.
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + SyncSettings.apiKey(context))
            conn.outputStream.use { it.write(JSONObject().put("locations", locations).toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    when (code) {
                        301, 302, 303, 307, 308 ->
                            "le serveur redirige vers ${conn.getHeaderField("Location") ?: "une autre adresse"} ($code) : " +
                                "mets cette adresse dans les réglages (souvent https:// au lieu de http://)"
                        401 -> "clé API refusée (401)"
                        404 -> "adresse introuvable (404) : vérifie l'adresse du serveur"
                        else -> "le serveur a répondu $code"
                    }
                )
            }
            // Dawarich répond en JSON. Une page web à la place veut dire qu'on ne parle pas à l'API
            // (page de connexion d'un proxy, mauvaise adresse…) : rien n'a été enregistré.
            val body = runCatching { conn.inputStream.bufferedReader().readText() }.getOrDefault("").trim()
            val type = conn.contentType.orEmpty()
            if (type.contains("html", ignoreCase = true) || body.startsWith("<")) {
                throw IllegalStateException(
                    "le serveur a renvoyé une page web au lieu de l'API Dawarich ($code) : " +
                        "vérifie l'adresse et qu'aucune page de connexion ne protège le serveur"
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Copie de sauvegarde visible dans Téléchargements/Scenic Trajets (reste même si l'app est désinstallée). */
object LocalBackup {
    private const val FOLDER = "Scenic Trajets"

    fun save(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29 || !file.exists()) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, if (file.name.endsWith(".gpx")) "application/gpx+xml" else "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            Log.e(TAG, "Sauvegarde locale impossible pour ${file.name}", e)
        }
    }

    fun delete(context: Context, name: String) {
        if (Build.VERSION.SDK_INT < 29) return
        runCatching {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "%$FOLDER%"),
            )
        }
    }
}

/** Tâche en arrière-plan : envoie les trajets en attente, puis applique la durée de conservation. */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        var failed = false
        if (SyncSettings.isConfigured(ctx) && SyncSettings.autoUpload(ctx)) {
            for (trip in TripRecorder.trips(ctx)) {
                if (TripRecorder.isSent(ctx, trip.id)) continue
                try {
                    Dawarich.uploadTrip(ctx, trip.id)
                    TripRecorder.markSent(ctx, trip.id)
                    Log.i(TAG, "Trajet ${trip.id} envoyé à Dawarich")
                } catch (e: NothingToSend) {
                    Log.i(TAG, "Trajet ${trip.id} : ${e.message}")
                    TripRecorder.markSent(ctx, trip.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Envoi du trajet ${trip.id} échoué", e)
                    TripRecorder.setLastError(ctx, e.message ?: e.javaClass.simpleName)
                    failed = true
                    break
                }
            }
            if (!failed) TripRecorder.setLastError(ctx, null)
        }
        applyRetention(ctx)
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        /** Lance l'envoi dès qu'Internet est disponible. */
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("envoi", ExistingWorkPolicy.REPLACE, request)
        }

        /** Passage quotidien : rattrape les envois ratés et fait le ménage. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("menage", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Supprime les fichiers de points (CSV, GPX, copie dans Téléchargements) plus vieux que la durée choisie.
         * Un trajet pas encore envoyé à Dawarich n'est jamais supprimé. L'historique (résumés) est conservé.
         */
        fun applyRetention(context: Context) {
            val days = SyncSettings.retentionDays(context)
            if (days <= 0) return
            val limit = System.currentTimeMillis() - days * 24L * 3600_000L
            val needsUpload = SyncSettings.isConfigured(context) && SyncSettings.autoUpload(context)
            for (trip in TripRecorder.trips(context)) {
                if (trip.end >= limit) continue
                if (needsUpload && !TripRecorder.isSent(context, trip.id)) continue
                listOf(TripRecorder.pointsFile(context, trip.id), TripRecorder.gpxFile(context, trip.id)).forEach { f ->
                    if (f.exists()) {
                        f.delete()
                        LocalBackup.delete(context, f.name)
                    }
                }
            }
        }
    }
}
