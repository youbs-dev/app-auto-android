package fr.brice.scenictrajets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Réglage de l'alerte batterie basse. */
object AlertSettings {
    private const val PREFS = "alerte"
    val CHOICES = listOf(0, 10, 15, 20, 25, 30)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Seuil en %, 0 = alerte désactivée. */
    fun threshold(c: Context) = prefs(c).getInt("seuil", 20)
    fun setThreshold(c: Context, v: Int) = prefs(c).edit().putInt("seuil", v).apply()
}

/**
 * Alerte batterie basse : une seule fois par passage sous le seuil, avec une notification
 * qui s'affiche sur l'écran de la voiture et ouvre la liste des bornes proches.
 */
object BatteryAlert {
    private const val CHANNEL = "alerte_batterie"
    private const val NOTIFICATION_ID = 2
    const val EXTRA_SCREEN = "ecran"
    const val SCREEN_CHARGERS = "bornes"

    @Volatile private var alerted = false

    fun isLow(c: Context): Boolean {
        val t = AlertSettings.threshold(c)
        val pct = CarSnapshot.batteryPercent ?: return false
        return t > 0 && pct <= t
    }

    /** Appelé à chaque lecture de la batterie dans la voiture. */
    fun onBattery(c: CarContext, pct: Float) {
        val t = AlertSettings.threshold(c)
        if (t <= 0) return
        if (pct > t + 3) { alerted = false; return }
        if (pct > t || alerted) return
        alerted = true
        val text = String.format(Locale.FRANCE, "%.0f %% · %s", pct,
            CarSnapshot.rangeKm?.let { String.format(Locale.FRANCE, "%.0f km d'autonomie", it) } ?: "pense à recharger")
        runCatching { CarToast.makeText(c, "🪫 Batterie basse : $text", CarToast.LENGTH_LONG).show() }
        runCatching { notify(c, text) }.onFailure { Log.w("ScenicAlerte", "Notification impossible", it) }
    }

    private fun notify(c: Context, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Batterie basse", NotificationManager.IMPORTANCE_HIGH))
        val open = CarPendingIntent.getCarApp(
            c, 0,
            Intent(Intent.ACTION_VIEW)
                .setComponent(ComponentName(c, TestCarAppService::class.java))
                .putExtra(EXTRA_SCREEN, SCREEN_CHARGERS),
            0,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Batterie basse")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle("🪫 Batterie basse")
                    .setContentText("$text · Touche pour voir les bornes proches")
                    .setContentIntent(open)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )
            .build()
        NotificationManagerCompat.from(c).notify(NOTIFICATION_ID, n)
    }
}

/** Une borne de recharge trouvée dans OpenStreetMap. */
data class Charger(
    val name: String,
    val details: String,
    val lat: Double,
    val lon: Double,
    val distanceM: Float,
)

/** Recherche des bornes autour d'un point, dans la base libre OpenStreetMap (sans clé). */
object ChargerSearch {

    fun near(lat: Double, lon: Double, radiusM: Int = 15_000, max: Int = 20): List<Charger> {
        val query = "[out:json][timeout:20];nwr[\"amenity\"=\"charging_station\"](around:$radiusM,$lat,$lon);out center 60;"
        val url = URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"))
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 25_000
        conn.setRequestProperty("User-Agent", "ScenicTrajets/1.0 (Android)")
        val body = try {
            if (conn.responseCode != 200) throw Exception("serveur des bornes : erreur ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<Charger>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val center = e.optJSONObject("center")
            val la = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: continue
            val lo = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: continue
            if (la.isNaN() || lo.isNaN()) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            if (tags.optString("access") == "private") continue
            val d = FloatArray(1)
            Location.distanceBetween(lat, lon, la, lo, d)
            out += Charger(
                name = tags.optString("name").ifBlank { tags.optString("operator").ifBlank { "Borne de recharge" } },
                details = details(tags),
                lat = la, lon = lo, distanceM = d[0],
            )
        }
        return out.sortedBy { it.distanceM }.take(max)
    }

    /** « CCS 150 kW · Type 2 · 4 points · Ionity ». */
    private fun details(t: JSONObject): String {
        val parts = mutableListOf<String>()
        if (t.has("socket:type2_combo")) {
            parts += "CCS" + (power(t.optString("socket:type2_combo:output"))?.let { " $it" } ?: "")
        }
        if (t.has("socket:type2")) {
            parts += "Type 2" + (power(t.optString("socket:type2:output"))?.let { " $it" } ?: "")
        }
        t.optString("capacity").toIntOrNull()?.let { parts += if (it == 1) "1 point" else "$it points" }
        t.optString("operator").takeIf { it.isNotBlank() && it != t.optString("name") }?.let { parts += it }
        if (t.optString("fee") == "no") parts += "gratuite"
        return parts.joinToString(" · ").ifEmpty { "Détails inconnus" }
    }

    private fun power(v: String): String? {
        val n = Regex("[0-9]+([.,][0-9]+)?").find(v)?.value ?: return null
        return n.replace(',', '.').toFloatOrNull()?.let { String.format(Locale.FRANCE, "%.0f kW", it) }
    }
}

/** Bornes proches, sur une carte dans la voiture. Toucher une borne lance le guidage. */
@Suppress("DEPRECATION")
class ChargersScreen(carContext: CarContext) : Screen(carContext) {

    private var chargers: List<Charger>? = null
    private var error: String? = null
    private var origin: Pair<Double, Double>? = null

    init {
        origin = TripPlaces.currentPosition(carContext)
        val o = origin
        if (o == null) {
            error = "Position inconnue : autorise la localisation dans l'app sur le téléphone"
            chargers = emptyList()
        } else {
            Thread {
                val result = runCatching { ChargerSearch.near(o.first, o.second) }
                androidx.core.content.ContextCompat.getMainExecutor(carContext).execute {
                    chargers = result.getOrDefault(emptyList())
                    error = result.exceptionOrNull()?.let { "Recherche impossible (${it.message ?: "pas d'Internet ?"})" }
                    invalidate()
                }
            }.start()
        }
    }

    /**
     * Si Android Auto refuse la carte, on essaie une simple liste, puis un message avec l'erreur :
     * l'app ne plante plus et on sait ce qui ne va pas.
     */
    override fun onGetTemplate(): Template = try {
        mapTemplate()
    } catch (e: Exception) {
        Log.e("ScenicBornes", "Carte des bornes refusée", e)
        try {
            listTemplate()
        } catch (e2: Exception) {
            Log.e("ScenicBornes", "Liste des bornes refusée", e2)
            MessageTemplate.Builder("Bornes indisponibles.\n${e.javaClass.simpleName} : ${e.message ?: ""}")
                .setTitle("Bornes proches")
                .setHeaderAction(Action.BACK)
                .build()
        }
    }

    /** Liste sans carte : secours si la carte n'est pas acceptée par la voiture. */
    private fun listTemplate(): Template {
        val b = ListTemplate.Builder().setTitle("Bornes proches").setHeaderAction(Action.BACK)
        val list = chargers ?: return b.setLoading(true).build()
        val items = ItemList.Builder().setNoItemsMessage(error ?: "Aucune borne à moins de 15 km")
        list.take(limit()).forEach { ch ->
            items.addItem(
                Row.Builder()
                    .setTitle(ch.name + " · " + distanceText(ch))
                    .addText(ch.details)
                    .setOnClickListener { navigate(ch) }
                    .build()
            )
        }
        return b.setSingleList(items.build()).build()
    }

    private fun limit() = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
    }.getOrDefault(6).coerceIn(1, 20)

    private fun distanceText(ch: Charger) =
        if (ch.distanceM < 1000) String.format(Locale.FRANCE, "%.0f m", ch.distanceM)
        else String.format(Locale.FRANCE, "%.1f km", ch.distanceM / 1000f)

    private fun mapTemplate(): Template {
        val builder = PlaceListMapTemplate.Builder()
            .setTitle("Bornes proches")
            .setHeaderAction(Action.BACK)
        origin?.let { (la, lo) -> builder.setAnchor(Place.Builder(CarLocation.create(la, lo)).build()) }
        val list = chargers
        if (list == null) {
            return builder.setLoading(true).build()
        }
        val limit = limit()
        val items = ItemList.Builder().setNoItemsMessage(error ?: "Aucune borne à moins de 15 km")
        list.take(limit).forEachIndexed { i, ch ->
            val title = SpannableString("  · " + ch.name)
            title.setSpan(
                DistanceSpan.create(
                    if (ch.distanceM < 1000) Distance.create(ch.distanceM.toDouble(), Distance.UNIT_METERS)
                    else Distance.create(ch.distanceM / 1000.0, Distance.UNIT_KILOMETERS)
                ),
                0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
            items.addItem(
                Row.Builder()
                    .setTitle(title)
                    .addText(ch.details)
                    .setMetadata(
                        Metadata.Builder().setPlace(
                            Place.Builder(CarLocation.create(ch.lat, ch.lon))
                                .setMarker(PlaceMarker.Builder().setLabel((i + 1).toString()).build())
                                .build()
                        ).build()
                    )
                    .setOnClickListener { navigate(ch) }
                    .build()
            )
        }
        return builder.setItemList(items.build()).build()
    }

    private fun navigate(ch: Charger) {
        val uri = Uri.parse("geo:${ch.lat},${ch.lon}?q=${ch.lat},${ch.lon}(" + Uri.encode(ch.name) + ")")
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Impossible d'ouvrir le guidage", CarToast.LENGTH_LONG).show()
        }
    }
}
