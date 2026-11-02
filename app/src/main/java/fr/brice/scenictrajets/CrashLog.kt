package fr.brice.scenictrajets

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal du dernier plantage : l'erreur exacte est gardée dans un fichier,
 * puis affichée dans Réglages (et Diagnostic) pour pouvoir la transmettre.
 */
object CrashLog {
    @Volatile private var installed = false

    fun file(c: Context) = File(c.filesDir, "plantage.txt")

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread.name, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun write(c: Context, thread: String, e: Throwable) {
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().lines().take(80).joinToString("\n")
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.FRANCE).format(Date())
        file(c).writeText("$date · version $version · fil $thread\n$trace\n")
    }

    fun last(c: Context): String? = file(c).takeIf { it.exists() }?.readText()?.ifBlank { null }

    /** Première ligne utile : « 2026-10-28 21:40 · IllegalArgumentException : … ». */
    fun summary(c: Context): String? = last(c)?.lines()?.let { l ->
        listOfNotNull(l.getOrNull(0)?.substringBefore(" · "), l.getOrNull(1)?.substringAfterLast('.')?.take(120))
            .joinToString(" · ")
    }

    fun clear(c: Context) { file(c).delete() }
}
