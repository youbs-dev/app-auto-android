package fr.brice.scenictrajets

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import fr.brice.scenictrajets.Ui.show
import java.io.File

private const val REQUEST_PERMISSIONS = 1
private const val MAX_TRIPS_SHOWN = 60

/**
 * Écran du téléphone : l'état de l'app en un coup d'œil, puis l'historique des trajets.
 * Les réglages (trajets automatiques, Dawarich, conservation) sont dans [SettingsActivity].
 */
class MainActivity : Activity() {

    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var fixButton: android.widget.Button
    private lateinit var permissionsLink: android.widget.Button
    private lateinit var currentCard: LinearLayout
    private lateinit var currentText: TextView
    private lateinit var totalsText: TextView
    private lateinit var history: LinearLayout
    private var fixAction: () -> Unit = {}
    private var shownActive: Boolean? = null

    private val tripListener: () -> Unit = { runOnUiThread { onTripChanged() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this

        statusTitle = Ui.title(c)
        statusText = Ui.secondary(c)
        fixButton = Ui.button(c, "", primary = true) { fixAction() }
        permissionsLink = Ui.button(c, "Ouvrir les autorisations de l'app") { openAppSettings() }
        val statusCard = Ui.card(c).apply {
            addView(statusTitle); addView(statusText); addView(fixButton); addView(permissionsLink)
        }

        currentText = Ui.text(c, size = 16f)
        currentCard = Ui.card(c).apply {
            addView(Ui.title(c, "🔴 Trajet en cours")); addView(currentText)
        }

        totalsText = Ui.text(c)
        val totalsCard = Ui.card(c).apply { addView(totalsText) }

        history = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        val column = Ui.column(c).apply {
            addView(statusCard)
            addView(currentCard)
            addView(Ui.header(c, "RÉSUMÉ"))
            addView(totalsCard)
            addView(history)
        }
        setContentView(ScrollView(c).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        shownActive = null
        onTripChanged()
        TripRecorder.addListener(tripListener)
        SyncWorker.schedule(this)
    }

    override fun onPause() {
        TripRecorder.removeListener(tripListener)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Réglages").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Exporter tous les trajets (GPX, Dawarich)")
        menu.add(0, 3, 2, "Exporter tous les trajets (CSV, tableur)")
        menu.add(0, 4, 3, "Ouvrir Android Auto")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, SettingsActivity::class.java))
            2 -> exportAll("gpx", "*/*")
            3 -> exportAll("csv", "text/csv")
            4 -> openAndroidAuto(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // --- État : une seule chose à faire à la fois -------------------------------------------

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
        val auto = DrivingDetector.isEnabled(this)
        val background = DrivingDetector.hasBackgroundLocation(this)
        val syncError = if (SyncSettings.isConfigured(this)) TripRecorder.lastError(this) else null

        when {
            missing.isNotEmpty() -> {
                statusTitle.text = "⚠️ Il manque des autorisations"
                statusText.text = "L'app doit pouvoir lire la batterie de la voiture et enregistrer le parcours GPS."
                fixButton.text = "Autoriser l'accès"
                fixAction = { requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS) }
            }
            auto && !background && Build.VERSION.SDK_INT >= 29 -> {
                statusTitle.text = "⚠️ Encore une étape"
                statusText.text = "Pour démarrer les trajets tout seuls, choisis « Toujours autoriser » pour la localisation."
                fixButton.text = "Autoriser la localisation « Toujours »"
                fixAction = {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_PERMISSIONS)
                }
            }
            syncError != null -> {
                statusTitle.text = "⚠️ Envoi vers Dawarich en échec"
                statusText.text = "$syncError\nLes trajets restent sur le téléphone et repartiront tout seuls."
                fixButton.text = "Voir les réglages Dawarich"
                fixAction = { startActivity(Intent(this, SettingsActivity::class.java)) }
            }
            else -> {
                statusTitle.text = "✅ Tout est prêt"
                statusText.text = if (auto) "Les trajets s'enregistrent tout seuls dès que tu roules avec Android Auto."
                else "Démarre un trajet depuis l'écran du Scénic (trajets automatiques désactivés dans les réglages)."
                fixAction = {}
            }
        }
        val needsFix = missing.isNotEmpty() || (auto && !background && Build.VERSION.SDK_INT >= 29) || syncError != null
        fixButton.show(needsFix)
        permissionsLink.show(missing.isNotEmpty())
        // Enregistre (ou ré-enregistre) la détection de conduite dès que les autorisations le permettent.
        DrivingDetector.register(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // --- Trajets ---------------------------------------------------------------------------

    /** Appelé à chaque position GPS : on ne relit l'historique que quand un trajet commence ou finit. */
    private fun onTripChanged() {
        val active = TripRecorder.isActive
        currentCard.show(active)
        if (active) currentText.text = TripStats.current()
        if (shownActive != active) {
            shownActive = active
            refreshHistory()
        }
    }

    private fun refreshHistory() {
        val trips = TripRecorder.trips(this)
        totalsText.text = "Cette semaine : " + TripStats.total(trips, TripStats.startOfWeek()) +
            "\nCe mois : " + TripStats.total(trips, TripStats.startOfMonth())

        history.removeAllViews()
        if (trips.isEmpty()) {
            history.addView(Ui.header(this, "HISTORIQUE"))
            history.addView(Ui.card(this).apply {
                addView(Ui.secondary(this@MainActivity,
                    "Aucun trajet pour l'instant. Ils apparaîtront ici après chaque trajet de plus de 200 m."))
            })
            return
        }
        val configured = SyncSettings.isConfigured(this)
        var lastDay = ""
        trips.take(MAX_TRIPS_SHOWN).forEach { t ->
            val day = TripStats.dayLabel(t.start)
            if (day != lastDay) {
                history.addView(Ui.header(this, day.uppercase()))
                lastDay = day
            }
            val cloud = when {
                !configured -> ""
                TripRecorder.isSent(this, t.id) -> "  ☁️"
                else -> "  ⏳"
            }
            history.addView(Ui.card(this).apply {
                addView(Ui.text(this@MainActivity, TripStats.hours(t) + cloud, 16f))
                addView(Ui.text(this@MainActivity, TripStats.summary(t)))
                addView(Ui.secondary(this@MainActivity, TripStats.battery(t)))
                isClickable = true
                setOnClickListener { tripMenu(t) }
            })
        }
        if (trips.size > MAX_TRIPS_SHOWN) {
            history.addView(Ui.secondary(this,
                "Les trajets plus anciens sont dans l'export CSV (menu ⋮)."))
        }
    }

    /** Toucher un trajet : le partager seul. */
    private fun tripMenu(t: Trip) {
        AlertDialog.Builder(this)
            .setTitle(TripStats.shortDate(t) + " · " + TripStats.hours(t))
            .setItems(arrayOf("Partager le parcours (GPX)", "Partager les points (CSV)")) { _, which ->
                val f = if (which == 0) TripRecorder.gpxFile(this, t.id) else TripRecorder.pointsFile(this, t.id)
                share(listOf(f), if (which == 0) "*/*" else "text/csv")
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Partage les fichiers d'une extension donnée (mail, Drive, Nextcloud…). */
    private fun exportAll(ext: String, mime: String) {
        val files = TripRecorder.dir(this).listFiles { f -> f.name.endsWith(".$ext") }?.sortedBy { it.name }
        share(files.orEmpty(), mime)
    }

    private fun share(files: List<File>, mime: String) {
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) {
            Toast.makeText(this, "Fichier introuvable (supprimé après la durée de conservation ?)", Toast.LENGTH_LONG).show()
            return
        }
        val uris = ArrayList(existing.map { FileProvider.getUriForFile(this, "$packageName.fichiers", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, "Trajets Scénic")
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Partager")) }
    }
}
