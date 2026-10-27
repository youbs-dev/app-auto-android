package fr.brice.scenictrajets

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

private const val REQUEST_PERMISSIONS = 1
private const val MAX_TRIPS_SHOWN = 60

/** Ce que le bandeau d'état doit dire, et le seul bouton utile. */
private data class Status(
    val ok: Boolean,
    val title: String,
    val text: String,
    val button: String? = null,
    val action: () -> Unit = {},
    val showAppSettings: Boolean = false,
)

/**
 * Écran du téléphone : l'état de l'app en un coup d'œil, puis l'historique des trajets.
 * Les réglages (thème, trajets automatiques, Dawarich, conservation) sont dans [SettingsActivity].
 */
class MainActivity : ComponentActivity() {

    /** Change à chaque position GPS ou à chaque retour sur l'écran : Compose redessine. */
    private var tripTick by mutableIntStateOf(0)
    private var statusTick by mutableIntStateOf(0)
    private val tripListener: () -> Unit = { runOnUiThread { tripTick++ } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScenicTheme { HomeContent() } }
    }

    override fun onResume() {
        super.onResume()
        statusTick++
        tripTick++
        TripRecorder.addListener(tripListener)
        SyncWorker.schedule(this)
        // Enregistre (ou ré-enregistre) la détection de conduite dès que les autorisations le permettent.
        DrivingDetector.register(this)
    }

    override fun onPause() {
        TripRecorder.removeListener(tripListener)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        statusTick++
        DrivingDetector.register(this)
    }

    // --- Écran ------------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeContent() {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        var menu by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<Trip?>(null) }

        // On ne relit l'historique que quand un trajet commence ou finit (pas à chaque position GPS).
        val active = tripTick.let { TripRecorder.isActive }
        val trips = remember(active, statusTick) { TripRecorder.trips(this) }
        val status = remember(statusTick) { computeStatus() }
        val configured = remember(statusTick) { SyncSettings.isConfigured(this) }

        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text("Scénic Trajets") },
                    scrollBehavior = scroll,
                    actions = {
                        IconButton(onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                        }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Plus")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Exporter tout (GPX, Dawarich)") },
                                onClick = { menu = false; exportAll("gpx", "*/*") },
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter tout (CSV, tableur)") },
                                onClick = { menu = false; exportAll("csv", "text/csv") },
                            )
                            DropdownMenuItem(
                                text = { Text("Ouvrir Android Auto") },
                                onClick = { menu = false; openAndroidAuto(this@MainActivity) },
                            )
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
                item { StatusCard(status) }
                if (active) item { CurrentTripCard(tripTick) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard("Cette semaine", trips, TripStats.startOfWeek(), Modifier.weight(1f))
                        StatCard("Ce mois", trips, TripStats.startOfMonth(), Modifier.weight(1f))
                    }
                }
                if (trips.isEmpty()) {
                    item { SectionTitle("Historique") }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Text(
                                "Aucun trajet pour l'instant. Ils apparaîtront ici après chaque trajet de plus de 200 m.",
                                modifier = Modifier.padding(20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val shown = trips.take(MAX_TRIPS_SHOWN)
                shown.groupBy { TripStats.dayLabel(it.start) }.forEach { (day, dayTrips) ->
                    item(key = "jour-$day") { SectionTitle(day) }
                    items(dayTrips, key = { it.id }) { t -> TripCard(t, configured) { selected = t } }
                }
                if (trips.size > MAX_TRIPS_SHOWN) {
                    item {
                        Text(
                            "Les trajets plus anciens sont dans l'export CSV (menu ⋮).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        selected?.let { t ->
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text(TripStats.shortDate(t) + " · " + TripStats.hours(t)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(TripStats.summary(t))
                        Text(TripStats.battery(t))
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { selected = null; share(listOf(TripRecorder.gpxFile(this@MainActivity, t.id)), "*/*") }) {
                            Text("Partager le parcours (GPX)")
                        }
                        TextButton(onClick = { selected = null; share(listOf(TripRecorder.pointsFile(this@MainActivity, t.id)), "text/csv") }) {
                            Text("Partager les points (CSV)")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selected = null }) { Text("Fermer") } },
            )
        }
    }

    @Composable
    private fun StatusCard(s: Status) {
        val scheme = MaterialTheme.colorScheme
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = if (s.ok) scheme.primaryContainer else scheme.tertiaryContainer,
                contentColor = if (s.ok) scheme.onPrimaryContainer else scheme.onTertiaryContainer,
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(s.text, style = MaterialTheme.typography.bodyMedium)
                s.button?.let {
                    Button(onClick = s.action, modifier = Modifier.fillMaxWidth()) { Text(it) }
                }
                if (s.showAppSettings) {
                    TextButton(onClick = { openAppSettings() }) { Text("Ouvrir les autorisations de l'app") }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun CurrentTripCard(@Suppress("UNUSED_PARAMETER") tick: Int) {
        val scheme = MaterialTheme.colorScheme
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Trajet en cours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(TripStats.current(), style = MaterialTheme.typography.headlineSmall)
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun StatCard(label: String, trips: List<Trip>, since: Long, modifier: Modifier) {
        val list = trips.filter { it.start >= since }
        val km = list.sumOf { it.distanceKm }
        val kwh = list.mapNotNull { TripStats.kwh(it) }.sum()
        Card(
            modifier = modifier,
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    String.format(Locale.FRANCE, "%.0f km", km),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    (if (list.size == 1) "1 trajet" else "${list.size} trajets") +
                        (if (kwh > 0) String.format(Locale.FRANCE, " · %.0f kWh", kwh) else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp),
        )
    }

    @Composable
    private fun TripCard(t: Trip, configured: Boolean, onClick: () -> Unit) {
        val cloud = when {
            !configured -> null
            TripRecorder.isSent(this, t.id) -> "☁️ envoyé"
            else -> "⏳ à envoyer"
        }
        Card(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(TripStats.hours(t), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    cloud?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(TripStats.summary(t), style = MaterialTheme.typography.bodyLarge)
                Text(TripStats.battery(t), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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

    private fun computeStatus(): Status {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val auto = DrivingDetector.isEnabled(this)
        val background = DrivingDetector.hasBackgroundLocation(this)
        val syncError = if (SyncSettings.isConfigured(this)) TripRecorder.lastError(this) else null
        return when {
            missing.isNotEmpty() -> Status(
                false, "Il manque des autorisations",
                "L'app doit pouvoir lire la batterie de la voiture et enregistrer le parcours GPS.",
                "Autoriser l'accès", { requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS) },
                showAppSettings = true,
            )
            auto && !background && Build.VERSION.SDK_INT >= 29 -> Status(
                false, "Encore une étape",
                "Pour démarrer les trajets tout seuls, choisis « Toujours autoriser » pour la localisation.",
                "Autoriser la localisation « Toujours »",
                { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_PERMISSIONS) },
            )
            syncError != null -> Status(
                false, "Envoi vers Dawarich en échec",
                "$syncError\nLes trajets restent sur le téléphone et repartiront tout seuls.",
                "Voir les réglages Dawarich", { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
            else -> Status(
                true, "Tout est prêt",
                if (auto) "Les trajets s'enregistrent tout seuls dès que tu roules avec Android Auto."
                else "Démarre un trajet depuis l'écran du Scénic (trajets automatiques désactivés dans les réglages).",
            )
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // --- Partage ----------------------------------------------------------------------------

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
