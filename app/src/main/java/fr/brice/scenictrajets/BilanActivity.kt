package fr.brice.scenictrajets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Bilan de chaque mois (km, énergie, coût, comparaison essence) et journal des recharges. */
class BilanActivity : ComponentActivity() {

    companion object {
        /** true : ouvrir le bilan par semaine (depuis la carte « Cette semaine »). */
        const val EXTRA_WEEKLY = "semaine"
    }

    private var tick by mutableIntStateOf(0)
    private val listener: () -> Unit = { runOnUiThread { tick++ } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VehicleStore.init(this)
        setContent { ScenicTheme { Content() } }
    }

    override fun onResume() {
        super.onResume()
        tick++
        ChargeLog.addListener(listener)
        ChargeLog.resolveNamesSoon(this)
    }

    override fun onPause() {
        ChargeLog.removeListener(listener)
        super.onPause()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Content() {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        var tab by remember { mutableIntStateOf(0) }
        // Ouvert depuis la carte « Cette semaine » : bilan par semaine.
        var weekly by remember { mutableStateOf(intent.getBooleanExtra(EXTRA_WEEKLY, false)) }
        var editing by remember { mutableStateOf<Charge?>(null) }
        var filter by remember { mutableStateOf<String?>(null) }
        val vehicles = VehicleStore.vehicles
        val charges = remember(tick, filter) { ChargeLog.charges(this).filter { filter == null || it.vehicleId == filter } }
        val trips = remember(tick, filter) { TripRecorder.trips(this).filter { filter == null || it.vehicleId == filter } }
        val months = remember(trips, charges, weekly) {
            if (weekly) TripStats.weeks(this, trips, charges) else TripStats.months(this, trips, charges)
        }
        val bands = remember(trips) { TripWeather.bands(this, trips) }

        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text(if (tab == 1) "Recharges" else if (weekly) "Bilan de la semaine" else "Bilan du mois") },
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
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Bilan", "Recharges").forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = tab == i,
                                onClick = { tab = i },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                }
                if (vehicles.size > 1) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Tous") })
                            vehicles.forEach { v ->
                                FilterChip(selected = filter == v.id, onClick = { filter = v.id }, label = { Text(v.name) })
                            }
                        }
                    }
                }
                if (tab == 0) {
                    item {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("Par mois", "Par semaine").forEachIndexed { i, label ->
                                SegmentedButton(
                                    selected = weekly == (i == 1),
                                    onClick = { weekly = i == 1 },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(label, maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                    if (bands.isNotEmpty()) item { WeatherCard(bands) }
                    if (months.isEmpty()) item { Empty("Le bilan apparaîtra après le premier trajet.") }
                    items(months, key = { it.label }) { MonthCard(it) }
                    item {
                        Hint(
                            "Le coût utilise le prix moyen de tes dernières recharges (ou le prix du kWh des réglages), " +
                                "pertes de recharge comprises (10 %). La comparaison essence se règle aussi dans les réglages."
                        )
                    }
                } else {
                    item {
                        Hint(
                            "Une recharge est notée quand la batterie a remonté entre deux lectures dans la voiture. " +
                                "Sur une borne publique, l'app note toute seule le nom de la borne (base OpenStreetMap). " +
                                "Touche une recharge pour corriger le lieu ou indiquer le prix payé. " +
                                "Les recharges suivantes au même endroit reprennent ce lieu et ce prix."
                        )
                    }
                    if (charges.isEmpty()) item { Empty("Aucune recharge détectée pour l'instant.") }
                    items(charges, key = { it.id }) { ch -> ChargeCard(ch) { editing = ch } }
                }
            }
        }

        editing?.let { ch -> EditDialog(ch) { editing = null } }
    }

    @Composable
    private fun MonthCard(m: TripStats.Month) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(TripStats.km(m.km), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Text(if (m.trips == 1) "1 trajet" else "${m.trips} trajets", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (m.kwh > 0) {
                        Column(Modifier.weight(1f)) {
                            Text(TripStats.euros(m.tripCost), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                            Text("d'électricité", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (m.kwh > 0) {
                    Line(
                        String.format(Locale.FRANCE, "%.0f kWh utilisés", m.kwh) +
                            (m.kwhPer100?.let { String.format(Locale.FRANCE, " · %.1f kWh/100 km en moyenne", it) } ?: "")
                    )
                    if (m.fuelCost > 0) {
                        Line(
                            "En essence : " + TripStats.euros(m.fuelCost) +
                                if (m.saving > 0) " · " + TripStats.euros(m.saving) + " économisés" else ""
                        )
                    }
                }
                if (m.measuredKm < m.km - 1) {
                    Hint(String.format(Locale.FRANCE, "Énergie mesurée sur %.0f km (batterie non lue sur les autres trajets).", m.measuredKm))
                }
                if (m.charges > 0) {
                    Line(
                        (if (m.charges == 1) "1 recharge" else "${m.charges} recharges") +
                            String.format(Locale.FRANCE, " · %.0f kWh · ", m.chargedKwh) + TripStats.euros(m.chargeCost)
                    )
                }
            }
        }
    }

    /** Conso moyenne par tranche de température : montre ce que coûte le froid. */
    @Composable
    private fun WeatherCard(bands: List<TripWeather.Band>) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Conso selon la température", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                bands.forEach { b ->
                    Row {
                        Text(b.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            (b.kwhPer100?.let { String.format(Locale.FRANCE, "%.1f kWh/100 km", it) } ?: "trop peu de km") +
                                (if (b.trips == 1) " · 1 trajet" else " · ${b.trips} trajets"),
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Hint("Température extérieure au milieu de chaque trajet (Open-Meteo). Le chauffage et la batterie froide font monter la conso en hiver.")
            }
        }
    }

    @Composable
    private fun ChargeCard(ch: Charge, onClick: () -> Unit) {
        Card(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "⚡ " + ch.place.ifEmpty { "Lieu à préciser" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(TripStats.charge(ch), style = MaterialTheme.typography.bodyLarge)
                Text(
                    TripStats.chargeWhen(ch) + String.format(Locale.FRANCE, " · %.3f €/kWh", ch.pricePerKwh),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun EditDialog(ch: Charge, close: () -> Unit) {
        var place by remember { mutableStateOf(ch.place) }
        var price by remember { mutableStateOf(String.format(Locale.FRANCE, "%.4f", ch.pricePerKwh).trimEnd('0').trimEnd(',')) }
        val c = this
        AlertDialog(
            onDismissRequest = close,
            title = { Text("Recharge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(TripStats.charge(ch))
                    OutlinedTextField(
                        value = place, onValueChange = { place = it },
                        label = { Text("Lieu") }, placeholder = { Text("Maison, Ionity Mâcon…") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = price, onValueChange = { price = it },
                        label = { Text("Prix payé (€ par kWh)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { ChargeLog.delete(c, ch.id); close() }) { Text("Supprimer cette recharge") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ChargeLog.update(c, ch.id, place.trim(), parsePrice(price) ?: ch.pricePerKwh)
                    close()
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Annuler") } },
        )
    }

    @Composable
    private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

    @Composable
    private fun Hint(text: String) =
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    @Composable
    private fun Empty(text: String) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Text(text, modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Lit « 0,25 » ou « 0.25 ». */
fun parsePrice(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()?.takeIf { it >= 0f }
