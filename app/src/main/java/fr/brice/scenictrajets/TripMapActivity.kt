package fr.brice.scenictrajets

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Carte d'un trajet (fond OpenStreetMap), le tracé coloré selon la vitesse. */
class TripMapActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ID = "trajet"

        /** Tranches de vitesse et leur couleur, de la plus lente à la plus rapide. */
        val SPEEDS = listOf(
            Triple(0f, "< 30 km/h", 0xFF2E7DD7.toInt()),
            Triple(30f, "30–50", 0xFF2EAD5B.toInt()),
            Triple(50f, "50–90", 0xFFE0B000.toInt()),
            Triple(90f, "90–110", 0xFFF07B1A.toInt()),
            Triple(110f, "> 110", 0xFFD83A34.toInt()),
        )

        fun colorFor(kmh: Float?): Int {
            val v = kmh ?: return AndroidColor.GRAY
            return SPEEDS.last { v >= it.first }.third
        }
    }

    private var map: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VehicleStore.init(this)
        // OpenStreetMap demande une application identifiée ; les tuiles sont gardées dans le cache de l'app.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tuiles")
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        setContent { ScenicTheme { Content(id) } }
    }

    override fun onResume() { super.onResume(); map?.onResume() }
    override fun onPause() { map?.onPause(); super.onPause() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Content(id: String) {
        val trip = remember { TripRecorder.trips(this).firstOrNull { it.id == id } }
        val points = remember { TripPlaces.points(this, id) }
        val places = remember { TripPlaces.label(this, id) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(trip?.let { TripStats.shortDate(it) + " · " + TripStats.hours(it) } ?: "Trajet")
                            places?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (points.size < 2) {
                    Text(
                        "Le parcours de ce trajet n'est plus sur le téléphone (supprimé après la durée de conservation).",
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> buildMap(ctx, points).also { map = it } },
                    )
                    Legend(trip, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
        }
    }

    private fun buildMap(ctx: android.content.Context, points: List<TrackPoint>): MapView {
        val view = MapView(ctx)
        view.setTileSource(TileSourceFactory.MAPNIK)
        view.setMultiTouchControls(true)
        val density = ctx.resources.displayMetrics.density

        // Un trait par portion de même couleur : la carte reste fluide même pour un long trajet.
        var current = mutableListOf<GeoPoint>()
        var color = colorFor(points.first().speedKmh)
        fun flush() {
            if (current.size < 2) return
            val line = Polyline(view)
            line.setPoints(current)
            line.outlinePaint.color = color
            line.outlinePaint.strokeWidth = 6 * density
            line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            view.overlays.add(line)
        }
        points.forEach { p ->
            val gp = GeoPoint(p.lat, p.lon)
            val c = colorFor(p.speedKmh)
            if (c != color) {
                current.add(gp)
                flush()
                current = mutableListOf(gp)
                color = c
            } else {
                current.add(gp)
            }
        }
        flush()

        listOf(points.first() to "Départ", points.last() to "Arrivée").forEach { (p, title) ->
            val m = Marker(view)
            m.position = GeoPoint(p.lat, p.lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            m.title = title
            view.overlays.add(m)
        }

        val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
        view.addOnFirstLayoutListener { _, _, _, _, _ ->
            view.zoomToBoundingBox(box.increaseByScale(1.2f), false)
        }
        return view
    }

    @Composable
    private fun Legend(trip: Trip?, modifier: Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trip?.let {
                    Text(TripStats.summary(it) + " · " + TripStats.batteryAndCost(this@TripMapActivity, it), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SPEEDS.forEach { (_, label, c) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c)))
                            Text(" $label", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
