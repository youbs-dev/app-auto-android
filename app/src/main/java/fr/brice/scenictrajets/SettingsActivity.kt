package fr.brice.scenictrajets

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

fun openAndroidAuto(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO"))
    runCatching { context.startActivity(intent) }
}

/** Réglages : trajets automatiques, Dawarich, conservation des fichiers, aide. */
class SettingsActivity : Activity() {

    private lateinit var urlField: EditText
    private lateinit var keyField: EditText
    private lateinit var uploadSwitch: Switch
    private lateinit var retentionSpinner: Spinner
    private lateinit var syncStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val c = this

        // Trajets automatiques
        val autoSwitch = Switch(c).apply {
            text = "Démarrer les trajets automatiquement"
            textSize = 16f
            setTextColor(Ui.textColor(c))
            isChecked = DrivingDetector.isEnabled(c)
            setOnCheckedChangeListener { _, on -> DrivingDetector.setEnabled(c, on) }
        }
        val autoCard = Ui.card(c).apply {
            addView(autoSwitch)
            addView(Ui.secondary(c,
                "L'enregistrement démarre dès que le téléphone détecte que tu roules avec Android Auto connecté. " +
                    "Il s'arrête quand Android Auto se déconnecte ou après 10 minutes à l'arrêt. " +
                    "Un trajet sans Android Auto (bus, autre voiture) est ignoré."))
        }

        // Dawarich
        urlField = EditText(c).apply {
            hint = "Adresse du serveur (ex. https://dawarich.exemple.fr)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(SyncSettings.url(c))
        }
        keyField = EditText(c).apply {
            hint = "Clé API (Dawarich > Compte > API key)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SyncSettings.apiKey(c))
        }
        uploadSwitch = Switch(c).apply {
            text = "Envoyer chaque trajet automatiquement"
            textSize = 16f
            setTextColor(Ui.textColor(c))
            isChecked = SyncSettings.autoUpload(c)
        }
        syncStatus = Ui.secondary(c)
        TripRecorder.lastError(c)?.let { if (SyncSettings.isConfigured(c)) syncStatus.text = "❌ Dernier envoi échoué : $it" }
        val syncCard = Ui.card(c).apply {
            addView(urlField)
            addView(keyField)
            addView(uploadSwitch)
            addView(Ui.button(c, "Enregistrer et tester la connexion", primary = true) { saveAndTest() })
            addView(Ui.button(c, "Envoyer maintenant les trajets en attente") {
                saveSettings()
                SyncWorker.runSoon(c)
                syncStatus.text = "Envoi lancé…"
            })
            addView(syncStatus)
        }

        // Conservation
        retentionSpinner = Spinner(c).apply {
            adapter = ArrayAdapter(
                c, android.R.layout.simple_spinner_dropdown_item,
                SyncSettings.RETENTION_CHOICES.map { it.second },
            )
            val current = SyncSettings.RETENTION_CHOICES.indexOfFirst { it.first == SyncSettings.retentionDays(c) }
            setSelection(if (current >= 0) current else 2)
        }
        val filesCard = Ui.card(c).apply {
            addView(Ui.text(c, "Garder les fichiers des trajets :"))
            addView(retentionSpinner)
            addView(Ui.secondary(c,
                "Une copie de chaque trajet est aussi rangée dans Téléchargements/Scenic Trajets. " +
                    "Passé ce délai, les fichiers sont supprimés, jamais avant d'avoir été envoyés à Dawarich. " +
                    "L'historique reste."))
        }

        // Autorisations et Android Auto
        val systemCard = Ui.card(c).apply {
            addView(Ui.secondary(c,
                "L'app s'affiche sur l'écran du Scénic via Android Auto. Elle a besoin de lire la batterie, " +
                    "l'autonomie et le kilométrage, et de la localisation pour le parcours GPS."))
            addView(Ui.button(c, "Autorisations de l'app") {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            })
            addView(Ui.button(c, "Ouvrir Android Auto") { openAndroidAuto(c) })
        }

        val column = Ui.column(c).apply {
            addView(Ui.header(c, "TRAJETS"))
            addView(autoCard)
            addView(Ui.header(c, "TRANSFERT VERS DAWARICH"))
            addView(syncCard)
            addView(Ui.header(c, "FICHIERS SUR LE TÉLÉPHONE"))
            addView(filesCard)
            addView(Ui.header(c, "ANDROID AUTO ET AUTORISATIONS"))
            addView(systemCard)
        }
        setContentView(ScrollView(c).apply { addView(column) })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onStop() {
        saveSettings()
        super.onStop()
    }

    private fun saveSettings() {
        SyncSettings.save(
            this, urlField.text.toString(), keyField.text.toString(), uploadSwitch.isChecked,
            SyncSettings.RETENTION_CHOICES[retentionSpinner.selectedItemPosition].first,
        )
    }

    private fun saveAndTest() {
        saveSettings()
        if (!SyncSettings.isConfigured(this)) {
            syncStatus.text = "⚠️ Renseigne l'adresse du serveur et la clé API."
            return
        }
        syncStatus.text = "Test en cours…"
        Thread {
            val error = Dawarich.test(this)
            runOnUiThread {
                syncStatus.text = if (error == null) "✅ Connexion à Dawarich réussie" else "❌ Échec : $error"
                if (error == null) SyncWorker.runSoon(this)
            }
        }.start()
    }
}
