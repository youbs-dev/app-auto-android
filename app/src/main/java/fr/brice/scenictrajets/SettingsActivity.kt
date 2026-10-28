package fr.brice.scenictrajets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

fun openAndroidAuto(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO"))
    runCatching { context.startActivity(intent) }
}

/** Réglages : apparence, trajets automatiques, Dawarich, conservation des fichiers. */
class SettingsActivity : ComponentActivity() {

    private var url by mutableStateOf("")
    private var key by mutableStateOf("")
    private var autoUpload by mutableStateOf(true)
    private var retention by mutableStateOf(60)
    private var syncStatus by mutableStateOf("")
    private var priceHome by mutableStateOf("")
    private var priceOffPeak by mutableStateOf("")
    private var fuelPrice by mutableStateOf("")
    private var fuelPer100 by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = SyncSettings.url(this)
        key = SyncSettings.apiKey(this)
        autoUpload = SyncSettings.autoUpload(this)
        retention = SyncSettings.retentionDays(this)
        priceHome = num(PriceSettings.homePrice(this))
        priceOffPeak = PriceSettings.offPeakPrice(this).takeIf { it > 0f }?.let { num(it) } ?: ""
        fuelPrice = num(PriceSettings.fuelPrice(this))
        fuelPer100 = num(PriceSettings.fuelPer100(this))
        TripRecorder.lastError(this)?.let { if (SyncSettings.isConfigured(this)) syncStatus = "❌ Dernier envoi échoué : $it" }
        setContent { ScenicTheme { SettingsContent() } }
    }

    override fun onStop() {
        saveSettings()
        super.onStop()
    }

    private fun saveSettings() {
        SyncSettings.save(this, url, key, autoUpload, retention)
        PriceSettings.save(
            this,
            parsePrice(priceHome) ?: PriceSettings.homePrice(this),
            parsePrice(priceOffPeak) ?: 0f,
            parsePrice(fuelPrice) ?: PriceSettings.fuelPrice(this),
            parsePrice(fuelPer100) ?: PriceSettings.fuelPer100(this),
        )
    }

    /** « 0,2516 » plutôt que « 0.2516000 ». */
    private fun num(v: Float) = String.format(java.util.Locale.FRANCE, "%.4f", v).trimEnd('0').trimEnd(',')

    private fun saveAndTest() {
        saveSettings()
        if (!SyncSettings.isConfigured(this)) {
            syncStatus = "⚠️ Renseigne l'adresse du serveur et la clé API."
            return
        }
        syncStatus = "Test en cours…"
        Thread {
            val error = Dawarich.test(this)
            runOnUiThread {
                syncStatus = if (error == null) "✅ Connexion à Dawarich réussie" else "❌ Échec : $error"
                if (error == null) SyncWorker.runSoon(this)
            }
        }.start()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsContent() {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val c = this
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text("Réglages") },
                    scrollBehavior = scroll,
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Section("Apparence") {
                        Text("Thème", style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val modes = ThemeMode.entries
                            modes.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = ThemePrefs.mode == m,
                                    onClick = { ThemePrefs.setMode(c, m) },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                ) { Text(m.label, maxLines = 1, softWrap = false) }
                            }
                        }
                        Hint("« Auto » suit le mode clair ou sombre du téléphone.")
                        if (ThemePrefs.dynamicAvailable) {
                            SwitchRow("Couleurs du fond d'écran", ThemePrefs.dynamic) { ThemePrefs.setDynamic(c, it) }
                            Hint("Reprend les couleurs de ton fond d'écran (Material You) au lieu du bleu Scénic.")
                        }
                    }
                }
                item {
                    Section("Trajets") {
                        var auto by remember { mutableStateOf(DrivingDetector.isEnabled(c)) }
                        SwitchRow("Démarrer les trajets automatiquement", auto) {
                            auto = it
                            DrivingDetector.setEnabled(c, it)
                        }
                        Hint(
                            "L'enregistrement démarre dès que le téléphone détecte que tu roules avec Android Auto connecté. " +
                                "Il s'arrête quand Android Auto se déconnecte ou après 10 minutes à l'arrêt. " +
                                "Un trajet sans Android Auto (bus, autre voiture) est ignoré."
                        )
                    }
                }
                item {
                    Section("Alerte batterie basse") {
                        var open by remember { mutableStateOf(false) }
                        var threshold by remember { mutableStateOf(AlertSettings.threshold(c)) }
                        Text("Prévenir sous", style = MaterialTheme.typography.bodyLarge)
                        Box {
                            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (threshold == 0) "Jamais" else "$threshold %")
                            }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                AlertSettings.CHOICES.forEach { v ->
                                    DropdownMenuItem(
                                        text = { Text(if (v == 0) "Jamais" else "$v %") },
                                        onClick = { threshold = v; AlertSettings.setThreshold(c, v); open = false },
                                    )
                                }
                            }
                        }
                        Hint(
                            "Sous ce seuil, une alerte s'affiche sur l'écran du Scénic. Touche-la, ou le bouton Bornes, " +
                                "pour voir les bornes proches (base OpenStreetMap) et lancer le guidage."
                        )
                    }
                }
                item {
                    Section("Prix") {
                        NumberField("Prix du kWh à la maison (€)", priceHome, "0,2516") { priceHome = it }
                        NumberField("Prix du kWh en heures creuses (€, facultatif)", priceOffPeak, "0,2068") { priceOffPeak = it }
                        Hint(
                            "Avec un prix heures creuses, les recharges à la maison sont comptées à ce prix. " +
                                "Le prix d'une recharge sur borne se corrige dans Bilan > Recharges."
                        )
                        NumberField("Prix de l'essence (€ par litre)", fuelPrice, "1,80") { fuelPrice = it }
                        NumberField("Consommation d'une voiture essence (L/100 km)", fuelPer100, "6,5") { fuelPer100 = it }
                        Hint("Sert à calculer ce que tes trajets t'auraient coûté en essence, dans le bilan du mois.")
                    }
                }
                item {
                    Section("Transfert vers Dawarich") {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it },
                            label = { Text("Adresse du serveur") },
                            placeholder = { Text("https://dawarich.exemple.fr") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = key, onValueChange = { key = it },
                            label = { Text("Clé API") },
                            supportingText = { Text("Dawarich > Compte > API key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SwitchRow("Envoyer chaque trajet automatiquement", autoUpload) { autoUpload = it }
                        Button(onClick = { saveAndTest() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Enregistrer et tester la connexion")
                        }
                        OutlinedButton(
                            onClick = { saveSettings(); SyncWorker.runSoon(c); syncStatus = "Envoi lancé…" },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Envoyer maintenant les trajets en attente") }
                        if (syncStatus.isNotEmpty()) Text(syncStatus, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Section("Fichiers sur le téléphone") {
                        var open by remember { mutableStateOf(false) }
                        val label = SyncSettings.RETENTION_CHOICES.firstOrNull { it.first == retention }?.second ?: "2 mois"
                        Text("Garder les fichiers des trajets", style = MaterialTheme.typography.bodyLarge)
                        Box {
                            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                SyncSettings.RETENTION_CHOICES.forEach { (days, name) ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { retention = days; open = false })
                                }
                            }
                        }
                        Hint(
                            "Une copie de chaque trajet est aussi rangée dans Téléchargements/Scenic Trajets. " +
                                "Passé ce délai, les fichiers sont supprimés, jamais avant d'avoir été envoyés à Dawarich. " +
                                "L'historique reste."
                        )
                    }
                }
                item {
                    Section("Android Auto et autorisations") {
                        Hint(
                            "L'app s'affiche sur l'écran du Scénic via Android Auto. Elle lit la batterie, l'autonomie " +
                                "et le kilométrage, et utilise la localisation pour le parcours GPS."
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Autorisations de l'app") }
                        OutlinedButton(onClick = { openAndroidAuto(c) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Ouvrir Android Auto")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                content()
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun NumberField(label: String, value: String, example: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value, onValueChange = onChange,
            label = { Text(label) },
            placeholder = { Text(example) },
            singleLine = true,
            isError = value.isNotBlank() && parsePrice(value) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
