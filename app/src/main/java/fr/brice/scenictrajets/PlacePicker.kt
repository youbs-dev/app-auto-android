package fr.brice.scenictrajets

import android.content.Context
import android.location.Geocoder
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Un résultat de recherche d'adresse. */
data class FoundPlace(val name: String, val detail: String, val lat: Double, val lon: Double)

/** Recherche d'une adresse ou d'un lieu par son nom. */
object PlaceSearch {
    private const val TAG = "ScenicRecherche"

    /** À appeler hors du fil principal. Géocodeur du téléphone d'abord, OpenStreetMap (Nominatim) sinon. */
    fun search(c: Context, query: String): List<FoundPlace> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        return fromGeocoder(c, q).ifEmpty { fromNominatim(c, q) }
    }

    @Suppress("DEPRECATION")
    private fun fromGeocoder(c: Context, q: String): List<FoundPlace> = try {
        if (!Geocoder.isPresent()) emptyList() else
            Geocoder(c, Locale.FRANCE).getFromLocationName(q, 6).orEmpty().map { a ->
                val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null }
                val town = listOfNotNull(a.postalCode, a.locality ?: a.subAdminArea).joinToString(" ").ifBlank { null }
                // Nom du lieu (entreprise, gare…) seulement s'il apporte quelque chose de plus que la rue.
                val name = a.featureName?.takeIf { f ->
                    f.any { it.isLetter() } && street?.contains(f) != true && f != a.locality
                }
                FoundPlace(
                    name = listOfNotNull(name, street).joinToString(", ").ifBlank { town ?: q },
                    detail = listOfNotNull(town, a.countryName).joinToString(", "),
                    lat = a.latitude, lon = a.longitude,
                )
            }
    } catch (e: Exception) {
        Log.w(TAG, "Géocodeur indisponible", e)
        emptyList()
    }

    private fun fromNominatim(c: Context, q: String): List<FoundPlace> = try {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=6&countrycodes=fr,be,ch,lu&accept-language=fr&q=" +
                URLEncoder.encode(q, "UTF-8")
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", c.packageName)
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val parts = o.optString("display_name").split(", ")
                FoundPlace(
                    name = parts.take(2).joinToString(", "),
                    detail = parts.drop(2).take(3).joinToString(", "),
                    lat = o.getString("lat").toDouble(), lon = o.getString("lon").toDouble(),
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Recherche OpenStreetMap impossible", e)
        emptyList()
    }
}

/** Fenêtre de recherche : on tape une adresse, on choisit un résultat. */
@Composable
fun PlaceSearchDialog(title: String, onDismiss: () -> Unit, onPick: (FoundPlace) -> Unit) {
    val c = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundPlace>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run() {
        if (query.trim().length < 3 || busy) return
        busy = true
        val q = query
        val app = c.applicationContext
        Thread {
            val found = PlaceSearch.search(app, q)
            android.os.Handler(android.os.Looper.getMainLooper()).post { results = found; busy = false }
        }.start()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Adresse ou lieu") },
                    placeholder = { Text("12 rue de la Paix, Lyon") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = { run() }, enabled = query.trim().length >= 3) { Text("Chercher") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    results == null -> Text(
                        "Tape au moins 3 lettres : une adresse, une ville, ou le nom d'une entreprise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    results!!.isEmpty() -> Text(
                        "Aucun lieu trouvé. Vérifie l'orthographe, ajoute la ville, ou vérifie la connexion Internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(results!!) { p ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp)
                            ) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                if (p.detail.isNotBlank()) Text(
                                    p.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Petite carte fixe centrée sur un lieu, avec un repère. Un appui appelle [onClick]. */
@Composable
fun MiniMap(lat: Double, lon: Double, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().height(150.dp).clip(MaterialTheme.shapes.large)) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                setupOsm(ctx)
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(false)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(16.0)
                }
            },
            update = { view ->
                val p = GeoPoint(lat, lon)
                view.controller.setCenter(p)
                view.overlays.removeAll { it is Marker }
                view.overlays.add(Marker(view).apply {
                    position = p
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setInfoWindow(null)
                })
                view.invalidate()
            },
            onRelease = { it.onDetach() },
        )
        // Couche transparente au-dessus : la carte ne bouge pas, et la liste des réglages défile normalement.
        Box(
            Modifier.matchParentSize().let { m -> if (onClick != null) m.clickable { onClick() } else m.clickable(enabled = false) {} }
        )
    }
}

/** OpenStreetMap demande une application identifiée ; les tuiles sont gardées dans le cache de l'app. */
fun setupOsm(c: Context) {
    Configuration.getInstance().apply {
        userAgentValue = c.packageName
        osmdroidBasePath = File(c.cacheDir, "osmdroid")
        osmdroidTileCache = File(c.cacheDir, "osmdroid/tuiles")
    }
}
