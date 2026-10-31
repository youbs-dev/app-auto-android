package fr.brice.scenictrajets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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

/** Réglages rangés en trois groupes (Mes véhicules, Trajets et coûts, Application), en cartes repliables. */
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
    private var autoStart by mutableStateOf(true)
    private var threshold by mutableStateOf(20)
    private var vehicles by mutableStateOf(emptyList<Vehicle>())
    private var routesTick by mutableStateOf(0)
    private var openSections by mutableStateOf(emptySet<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VehicleStore.init(this)
        url = SyncSettings.url(this)
        key = SyncSettings.apiKey(this)
        autoUpload = SyncSettings.autoUpload(this)
        retention = SyncSettings.retentionDays(this)
        priceHome = num(PriceSettings.homePrice(this))
        priceOffPeak = PriceSettings.offPeakPrice(this).takeIf { it > 0f }?.let { num(it) } ?: ""
        fuelPrice = num(PriceSettings.fuelPrice(this))
        fuelPer100 = num(PriceSettings.fuelPer100(this))
        autoStart = DrivingDetector.isEnabled(this)
        threshold = AlertSettings.threshold(this)
        vehicles = VehicleStore.vehicles
        openSections = getSharedPreferences("reglages_affichage", MODE_PRIVATE).getStringSet("ouverts", null)?.toSet() ?: emptySet()
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---------------- Mes véhicules ----------------
                item { GroupTitle("Mes véhicules") }
                item {
                    val summary = vehicles.joinToString(" · ") { it.name } +
                        (if (vehicles.any { it.toConfigure }) " · à régler" else "")
                    Section("vehicules", "🚗", "Véhicules", summary) {
                        var editing by remember { mutableStateOf<Vehicle?>(null) }
                        vehicles.forEach { v ->
                            OutlinedButton(onClick = { editing = v }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    v.name + String.format(java.util.Locale.FRANCE, " · %.0f kWh", v.batteryKwh) +
                                        (if (v.id == VehicleStore.currentId) " · connecté en dernier" else "") +
                                        (if (v.toConfigure) " · à régler" else "")
                                )
                            }
                        }
                        OutlinedButton(onClick = { editing = VehicleStore.newVehicle(c) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Ajouter un véhicule")
                        }
                        Hint(
                            "Chaque voiture est reconnue toute seule quand Android Auto se connecte, et ses trajets, recharges " +
                                "et bilans sont rangés à part. Une nouvelle voiture est ajoutée automatiquement : il suffit de la régler ici."
                        )
                        editing?.let { v ->
                            VehicleDialog(v, close = { editing = null; vehicles = VehicleStore.vehicles })
                        }
                    }
                }
                item {
                    Section("alerte", "🔋", "Alerte batterie basse", if (threshold == 0) "Jamais" else "Sous $threshold %") {
                        var open by remember { mutableStateOf(false) }
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
                            "Sous ce seuil, une alerte s'affiche sur l'écran de la voiture. Touche-la, ou le bouton Bornes, " +
                                "pour voir les bornes proches (base OpenStreetMap) et lancer le guidage."
                        )
                    }
                }

                // ---------------- Trajets et coûts ----------------
                item { GroupTitle("Trajets et coûts") }
                item {
                    val keep = SyncSettings.RETENTION_CHOICES.firstOrNull { it.first == retention }?.second ?: "2 mois"
                    val summary = (if (autoStart) "Automatique" else "À la main") + " · fichiers gardés " + keep.lowercase()
                    Section("enregistrement", "⏺", "Enregistrement", summary) {
                        SwitchRow("Démarrer les trajets automatiquement", autoStart) {
                            autoStart = it
                            DrivingDetector.setEnabled(c, it)
                        }
                        Hint(
                            "L'enregistrement démarre dès que le téléphone détecte que tu roules avec Android Auto connecté. " +
                                "Il s'arrête quand Android Auto se déconnecte ou après 10 minutes à l'arrêt. " +
                                "Un trajet sans Android Auto (bus, autre voiture) est ignoré."
                        )
                        var open by remember { mutableStateOf(false) }
                        Text("Garder les fichiers des trajets", style = MaterialTheme.typography.bodyLarge)
                        Box {
                            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(keep) }
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
                    fun state(key: String): String {
                        routesTick
                        val pos = if (key == "domicile") Routes.home(c) else Routes.work(c)
                        return when {
                            pos == null -> "pas encore trouvé"
                            Routes.isManual(c, key) -> "réglé à la main"
                            else -> "trouvé à partir de tes trajets"
                        }
                    }
                    val summary = "Domicile " + state("domicile").substringBefore(" à") + " · travail " + state("travail").substringBefore(" à")
                    Section("domicile", "🏠", "Domicile et travail", summary) {
                        listOf("domicile" to "Domicile", "travail" to "Travail").forEach { (key, label) ->
                            val manual = routesTick.let { Routes.isManual(c, key) }
                            Text("$label : " + state(key), style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    TripPlaces.currentPosition(c)?.let { Routes.setManual(c, key, it); routesTick++ }
                                }) { Text("Ici, maintenant") }
                                if (manual) OutlinedButton(onClick = { Routes.setManual(c, key, null); routesTick++ }) { Text("Automatique") }
                            }
                        }
                        Hint(
                            "L'app trouve ton domicile (là où tu arrives le soir) et ton travail (là où tu arrives le matin en semaine). " +
                                "Les trajets entre les deux sont marqués, et chaque trajet est comparé à la moyenne des trajets sur le même parcours."
                        )
                    }
                }
                item {
                    val summary = (priceHome.ifBlank { "?" }) + " €/kWh" +
                        (if (priceOffPeak.isNotBlank()) " · HC $priceOffPeak €" else "") +
                        " · essence " + fuelPrice.ifBlank { "?" } + " €"
                    Section("prix", "€", "Prix de l'énergie", summary) {
                        NumberField("Prix du kWh à la maison (€)", priceHome, "0,2516") { priceHome = it }
                        NumberField("Prix du kWh en heures creuses (€, facultatif)", priceOffPeak, "0,2068") { priceOffPeak = it }
                        Hint(
                            "Avec un prix heures creuses, les recharges à la maison sont comptées à ce prix. " +
                                "Le prix d'une recharge sur borne se corrige dans Bilan > Recharges."
                        )
                        NumberField("Prix de l'essence (€ par litre)", fuelPrice, "1,80") { fuelPrice = it }
                        NumberField("Consommation d'une voiture essence (L/100 km)", fuelPer100, "6,5") { fuelPer100 = it }
                        Hint("Sert à calculer ce que tes trajets t'auraient coûté en essence, dans le bilan.")
                    }
                }
                item {
                    val summary = when {
                        url.isBlank() || key.isBlank() -> "Pas configuré"
                        syncStatus.startsWith("❌") -> "Dernier envoi échoué"
                        autoUpload -> "Envoi automatique activé"
                        else -> "Envoi à la main"
                    }
                    Section("dawarich", "☁", "Dawarich", summary) {
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

                // ---------------- Application ----------------
                item { GroupTitle("Application") }
                item {
                    val summary = "Thème " + ThemePrefs.mode.label +
                        (if (ThemePrefs.dynamicAvailable && ThemePrefs.dynamic) " · couleurs du fond d'écran" else "")
                    Section("apparence", "🎨", "Apparence", summary) {
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
                    Section("autorisations", "🔐", "Android Auto et autorisations", "Autorisations de l'app, ouvrir Android Auto") {
                        Hint(
                            "L'app s'affiche sur l'écran de la voiture via Android Auto. Elle lit la batterie, l'autonomie " +
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
                item {
                    val crash = CrashLog.last(c)
                    val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
                    val summary = "Version $version" + (if (crash != null) " · un plantage enregistré" else " · aucun plantage")
                    Section("depannage", "🛠", "Dépannage", summary) {
                        if (crash == null) {
                            Hint("Aucun plantage enregistré. Si l'app plante, l'erreur exacte apparaîtra ici pour être envoyée.")
                        } else {
                            Hint("L'erreur exacte du dernier plantage. Envoie-la pour qu'elle soit corrigée.")
                            Text(
                                crash.lines().take(12).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                            Button(onClick = { shareCrash(crash) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Partager l'erreur")
                            }
                            OutlinedButton(onClick = { CrashLog.clear(c); recreate() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Effacer")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun GroupTitle(text: String) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
        )
    }

    /** Carte repliable : fermée, elle montre un résumé ; l'app retient lesquelles sont ouvertes. */
    @Composable
    private fun Section(
        id: String,
        icon: String,
        title: String,
        summary: String,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val isOpen = id in openSections
        val turn by animateFloatAsState(if (isOpen) 180f else 0f, label = "chevron")
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { toggleSection(id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                ) { Text(icon, style = MaterialTheme.typography.titleMedium) }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isOpen) "Replier" else "Déplier",
                    modifier = Modifier.rotate(turn),
                )
            }
            AnimatedVisibility(visible = isOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
            }
        }
    }

    private fun toggleSection(id: String) {
        openSections = if (id in openSections) openSections - id else openSections + id
        getSharedPreferences("reglages_affichage", MODE_PRIVATE).edit().putStringSet("ouverts", openSections).apply()
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun VehicleDialog(v: Vehicle, close: () -> Unit) {
        var name by remember { mutableStateOf(v.name) }
        var kwh by remember { mutableStateOf(num(v.batteryKwh)) }
        var message by remember { mutableStateOf<String?>(null) }
        val c = this
        androidx.compose.material3.AlertDialog(
            onDismissRequest = close,
            title = { Text("Véhicule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    NumberField("Capacité utile de la batterie (kWh)", kwh, "87") { kwh = it }
                    if (v.model.isNotEmpty()) Hint("Reconnu dans Android Auto comme « ${v.model} ».")
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (VehicleStore.vehicles.any { it.id == v.id } && VehicleStore.vehicles.size > 1) {
                        androidx.compose.material3.TextButton(onClick = {
                            if (VehicleStore.delete(c, v.id)) close()
                            else message = "Ce véhicule a des trajets : change d'abord leur véhicule, ou garde-le."
                        }) { Text("Supprimer ce véhicule") }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val k = parsePrice(kwh)
                    if (name.isBlank() || k == null || k < 5f) {
                        message = "Indique un nom et une capacité (par exemple 52 ou 87)."
                    } else {
                        VehicleStore.upsert(c, v.copy(name = name.trim().replace(";", ","), batteryKwh = k, toConfigure = false))
                        close()
                    }
                }) { Text("Enregistrer") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = close) { Text("Annuler") } },
        )
    }

    private fun shareCrash(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Scénic Trajets : plantage")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "Partager l'erreur")) }
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
