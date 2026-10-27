package fr.brice.scenictrajets

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ANDROID_AUTO = "com.google.android.projection.gearhead"
private const val REQUEST_CAR_PERMISSIONS = 1

/**
 * Écran affiché sur le téléphone : l'app tourne dans Android Auto,
 * cet écran sert à accorder l'accès aux données de la voiture et à retrouver l'app.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var tripsView: TextView
    private val tripListener: () -> Unit = { runOnUiThread { refreshTrips() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 18f }

        val askButton = Button(this).apply {
            text = "Autoriser l'accès (voiture + localisation)"
            setOnClickListener { askCarPermissions() }
        }

        val settingsButton = Button(this).apply {
            text = "Ouvrir les autorisations de l'app"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            }
        }

        val help = TextView(this).apply {
            textSize = 15f
            text = """

                L'app s'affiche sur l'écran du Scénic, via Android Auto.
                Les autorisations ci-dessus lui permettent de lire la batterie, l'autonomie et le kilométrage, et d'enregistrer le parcours GPS des trajets.
                Démarre et arrête un trajet depuis l'écran de la voiture.
            """.trimIndent()
        }

        val tripsTitle = TextView(this).apply {
            textSize = 20f
            text = "\nHistorique des trajets"
        }
        tripsView = TextView(this).apply { textSize = 15f }

        val exportButton = Button(this).apply {
            text = "Exporter les trajets en CSV"
            setOnClickListener { exportCsv() }
        }

        val autoButton = Button(this).apply {
            text = "Ouvrir les paramètres d'Android Auto"
            setOnClickListener { openAndroidAutoSettings() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(askButton)
            addView(settingsButton)
            addView(help)
            addView(autoButton)
            addView(tripsTitle)
            addView(exportButton)
            addView(tripsView)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshTrips()
        TripRecorder.addListener(tripListener)
    }

    override fun onPause() {
        TripRecorder.removeListener(tripListener)
        super.onPause()
    }

    private fun neededPermissions(): List<String> {
        val all = CAR_PERMISSIONS + Manifest.permission.ACCESS_FINE_LOCATION +
            Manifest.permission.ACCESS_COARSE_LOCATION
        return if (Build.VERSION.SDK_INT >= 33) all + Manifest.permission.POST_NOTIFICATIONS else all
    }

    private fun missingPermissions() = neededPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun refreshStatus() {
        val missing = missingPermissions()
        status.text = if (missing.isEmpty()) {
            "✅ Accès à la voiture et à la localisation : autorisé"
        } else {
            "⚠️ ${missing.size} autorisation(s) manquante(s) : touche le bouton ci-dessous"
        }
    }

    private fun askCarPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_CAR_PERMISSIONS)
        else refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshTrips() {
        val df = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE)
        val lines = mutableListOf<String>()
        if (TripRecorder.isActive) {
            lines += String.format(Locale.FRANCE, "🔴 Trajet en cours : %.1f km\n", TripRecorder.distanceKm)
        }
        val trips = TripRecorder.trips(this)
        if (trips.isEmpty() && !TripRecorder.isActive) lines += "Aucun trajet pour l'instant."
        trips.forEach { t ->
            val batt = if (t.batteryStart != null && t.batteryEnd != null) {
                String.format(Locale.FRANCE, "batterie %.0f %% → %.0f %% (−%.1f pts)",
                    t.batteryStart, t.batteryEnd, t.batteryStart - t.batteryEnd)
            } else "batterie non lue"
            lines += String.format(Locale.FRANCE, "%s\n%.1f km · %d min · %s\n",
                df.format(Date(t.start)), t.distanceKm, t.durationMin, batt)
        }
        tripsView.text = lines.joinToString("\n")
    }

    /** Partage trajets.csv et les fichiers de points GPS (mail, Drive, etc.). */
    private fun exportCsv() {
        val files = TripRecorder.dir(this).listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            tripsView.text = "Aucun fichier à exporter pour l'instant.\n\n" + tripsView.text
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fichiers", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Trajets Scénic")
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Exporter les trajets")) }
    }

    private fun openAndroidAutoSettings() {
        val intent = packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO"))
        runCatching { startActivity(intent) }
    }
}
