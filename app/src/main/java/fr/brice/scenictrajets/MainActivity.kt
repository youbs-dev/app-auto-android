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
import android.widget.Switch
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
    private lateinit var backgroundButton: Button
    private lateinit var autoSwitch: Switch
    private val tripListener: () -> Unit = { runOnUiThread { refreshTrips() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 18f }

        val askButton = Button(this).apply {
            text = "Autoriser l'accès (voiture + localisation)"
            setOnClickListener { askCarPermissions() }
        }

        backgroundButton = Button(this).apply {
            text = "Autoriser la localisation « Toujours »"
            setOnClickListener { askBackgroundLocation() }
        }

        autoSwitch = Switch(this).apply {
            text = "Démarrer les trajets automatiquement"
            textSize = 16f
            isChecked = DrivingDetector.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                DrivingDetector.setEnabled(this@MainActivity, on)
                refreshStatus()
            }
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
                Trajets automatiques : dès que le téléphone détecte que tu roules en voiture, l'enregistrement démarre. Il s'arrête quand Android Auto se déconnecte (voiture éteinte) ou après 10 minutes à l'arrêt. Un trajet sans Android Auto (bus, autre voiture) est ignoré.
                Tu peux aussi démarrer et arrêter un trajet à la main depuis l'écran de la voiture.
            """.trimIndent()
        }

        val tripsTitle = TextView(this).apply {
            textSize = 20f
            text = "\nHistorique des trajets"
        }
        tripsView = TextView(this).apply { textSize = 15f }

        val gpxButton = Button(this).apply {
            text = "Exporter pour Dawarich (GPX)"
            setOnClickListener { export("gpx", "*/*") }
        }
        val exportButton = Button(this).apply {
            text = "Exporter en CSV (tableur)"
            setOnClickListener { export("csv", "text/csv") }
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
            addView(backgroundButton)
            addView(autoSwitch)
            addView(settingsButton)
            addView(help)
            addView(autoButton)
            addView(tripsTitle)
            addView(gpxButton)
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
        val all = CAR_PERMISSIONS.toMutableList()
        all += Manifest.permission.ACCESS_FINE_LOCATION
        all += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 29) all += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) all += Manifest.permission.POST_NOTIFICATIONS
        return all
    }

    private fun missingPermissions() = neededPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun refreshStatus() {
        val missing = missingPermissions()
        val background = DrivingDetector.hasBackgroundLocation(this)
        val lines = mutableListOf(
            if (missing.isEmpty()) "✅ Accès à la voiture et à la localisation : autorisé"
            else "⚠️ ${missing.size} autorisation(s) manquante(s) : touche le premier bouton"
        )
        if (autoSwitch.isChecked) {
            lines += if (background) "✅ Trajets automatiques : prêts"
            else "⚠️ Trajets automatiques : choisis « Toujours autoriser » pour la localisation (deuxième bouton)"
        }
        status.text = lines.joinToString("\n")
        backgroundButton.visibility =
            if (autoSwitch.isChecked && !background) android.view.View.VISIBLE else android.view.View.GONE
        // Enregistre (ou ré-enregistre) la détection de conduite dès que les autorisations le permettent.
        DrivingDetector.register(this)
    }

    /** Android impose une demande séparée, après la localisation normale (page des réglages sur Android 11+). */
    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        if (!TripRecorder.hasLocationPermission(this)) {
            askCarPermissions()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_CAR_PERMISSIONS)
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
    /** Partage les fichiers d'une extension donnée (mail, Drive, Nextcloud…). */
    private fun export(ext: String, mime: String) {
        val files = TripRecorder.dir(this).listFiles { f -> f.name.endsWith(".$ext") }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) {
            tripsView.text = "Aucun fichier à exporter pour l'instant.\n\n" + tripsView.text
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fichiers", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
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
