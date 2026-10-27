package fr.brice.scenictrajets

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Calculs et textes communs à l'écran de la voiture et à celui du téléphone. */
object TripStats {

    /** Capacité utile de la batterie du Scénic E-Tech (87 kWh). */
    const val BATTERY_KWH = 87f

    private val hour = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val day = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val shortDay = SimpleDateFormat("EEE d MMM", Locale.FRANCE)

    /** Points de batterie utilisés (null si la batterie n'a pas été lue). */
    fun batteryUsed(t: Trip): Float? {
        val s = t.batteryStart ?: return null
        val e = t.batteryEnd ?: return null
        return s - e
    }

    fun kwh(t: Trip): Float? = batteryUsed(t)?.takeIf { it > 0 }?.let { it / 100f * BATTERY_KWH }

    /** Consommation estimée, seulement quand le trajet est assez long pour que le chiffre ait un sens. */
    fun kwhPer100(t: Trip): Float? {
        val k = kwh(t) ?: return null
        if (t.distanceKm < 3) return null
        return (k / t.distanceKm * 100).toFloat()
    }

    fun km(v: Double) = String.format(Locale.FRANCE, if (v < 10) "%.1f km" else "%.0f km", v)

    fun duration(min: Long) = if (min < 60) "$min min" else String.format(Locale.FRANCE, "%d h %02d", min / 60, min % 60)

    fun hours(t: Trip) = hour.format(Date(t.start)) + " → " + hour.format(Date(t.end))

    fun shortDate(t: Trip) = shortDay.format(Date(t.start)).replaceFirstChar { it.uppercase() }

    /** « 23,4 km · 35 min · 8 °C » : la ligne principale avec la température, si elle est connue. */
    fun summaryWithWeather(c: android.content.Context, t: Trip) =
        summary(t) + (TripWeather.of(c, t.id)?.let { " · " + TripWeather.label(it) } ?: "")

    /** Ligne principale : « 23,4 km · 35 min ». */
    fun summary(t: Trip) = km(t.distanceKm) + " · " + duration(t.durationMin)

    /** Ligne batterie : « Batterie 80 → 74 % · 18,5 kWh/100 km ». */
    fun battery(t: Trip): String {
        val s = t.batteryStart
        val e = t.batteryEnd
        if (s == null || e == null) return "Batterie non lue"
        val base = String.format(Locale.FRANCE, "Batterie %.0f → %.0f %%", s, e)
        return kwhPer100(t)?.let { base + String.format(Locale.FRANCE, " · %.1f kWh/100 km", it) } ?: base
    }

    /** « Aujourd'hui », « Hier » ou « lundi 25 octobre ». */
    fun dayLabel(time: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val d = startOfDay(time)
        return when (d) {
            today -> "Aujourd'hui"
            startOfDay(today - 1) -> "Hier"
            else -> day.format(Date(time)).replaceFirstChar { it.uppercase() }
        }
    }

    fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfWeek(): Long = Calendar.getInstance(Locale.FRANCE).apply {
        timeInMillis = startOfDay(System.currentTimeMillis())
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    }.timeInMillis

    fun startOfMonth(): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(System.currentTimeMillis())
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    /** « 5 trajets · 120 km · 21 kWh » pour les trajets commencés après [since]. */
    fun total(trips: List<Trip>, since: Long): String {
        val list = trips.filter { it.start >= since }
        if (list.isEmpty()) return "aucun trajet"
        val km = list.sumOf { it.distanceKm }
        val kwh = list.mapNotNull { kwh(it) }.sum()
        val n = if (list.size == 1) "1 trajet" else "${list.size} trajets"
        return n + " · " + km(km) + (if (kwh > 0) String.format(Locale.FRANCE, " · %.0f kWh", kwh) else "")
    }

    fun euros(v: Float) = String.format(Locale.FRANCE, if (v < 10) "%.2f €" else "%.0f €", v)

    /** « Batterie 80 → 74 % · 18,5 kWh/100 km · 1,20 € ». */
    fun batteryAndCost(c: android.content.Context, t: Trip): String =
        battery(t) + (PriceSettings.tripCost(c, t)?.let { " · " + euros(it) } ?: "")

    // --- Trajet en cours, en direct ---------------------------------------------------------

    /** Ce que l'on sait du trajet en cours, pour l'affichage en direct. */
    class Live(
        val distanceKm: Double,
        val durationMin: Long,
        val speedKmh: Float?,
        val avgKmh: Float?,
        val maxKmh: Float,
        val batteryStart: Float?,
        val batteryNow: Float?,
        val rangeKm: Float?,
        val kwh: Float?,
        val kwhPer100: Float?,
        val cost: Float?,
    ) {
        val batteryUsed: Float? get() = batteryStart?.let { s -> batteryNow?.let { s - it } }
    }

    fun live(c: android.content.Context): Live {
        val ms = System.currentTimeMillis() - TripRecorder.startTime
        val dist = TripRecorder.distanceKm
        val start = TripRecorder.batteryAtStart
        val now = CarSnapshot.batteryPercent
        val kwh = start?.let { s -> now?.let { n -> (s - n).takeIf { it > 0 }?.let { it / 100f * BATTERY_KWH } } }
        return Live(
            distanceKm = dist,
            durationMin = ms / 60_000,
            speedKmh = TripRecorder.speedKmh,
            avgKmh = if (ms > 60_000 && dist > 0.5) (dist / (ms / 3_600_000.0)).toFloat() else null,
            maxKmh = TripRecorder.maxSpeedKmh,
            batteryStart = start,
            batteryNow = now,
            rangeKm = CarSnapshot.rangeKm,
            kwh = kwh,
            // Le % n'est précis qu'au point près : on attend 3 km et 1 point utilisé.
            kwhPer100 = kwh?.takeIf { dist >= 3 && it >= 0.9f }?.let { (it / dist * 100).toFloat() },
            cost = kwh?.let { it * PriceSettings.pricePerBatteryKwh(c) },
        )
    }

    fun kmh(v: Float?) = v?.let { String.format(Locale.FRANCE, "%.0f km/h", it) } ?: "– km/h"

    /** « 52 km/h · moyenne 38 km/h ». */
    fun liveSpeed(l: Live) = kmh(l.speedKmh) + (l.avgKmh?.let { " · moyenne " + kmh(it) } ?: "")

    /** « −6 % · 5,2 kWh · 17,8 kWh/100 km · 1,30 € ». */
    fun liveEnergy(l: Live): String {
        val used = l.batteryUsed ?: return "Batterie non lue pour l'instant"
        if (used < 0.5f) return "Pas encore de batterie utilisée"
        val parts = mutableListOf(String.format(Locale.FRANCE, "−%.0f %%", used))
        l.kwh?.let { parts += String.format(Locale.FRANCE, "%.1f kWh", it) }
        l.kwhPer100?.let { parts += String.format(Locale.FRANCE, "%.1f kWh/100 km", it) }
        l.cost?.let { parts += euros(it) }
        return parts.joinToString(" · ")
    }

    // --- Bilan du mois -----------------------------------------------------------------------

    class Month(
        val label: String,
        val trips: Int,
        val km: Double,
        /** Kilomètres des trajets où la batterie a été lue (base des calculs d'énergie). */
        val measuredKm: Double,
        val kwh: Float,
        val tripCost: Float,
        val charges: Int,
        val chargedKwh: Float,
        val chargeCost: Float,
        val fuelCost: Float,
    ) {
        val kwhPer100: Float? get() = if (measuredKm >= 10) (kwh / measuredKm * 100).toFloat() else null
        val saving: Float get() = fuelCost - tripCost
    }

    private val monthKey = SimpleDateFormat("yyyy-MM", Locale.FRANCE)
    private val monthName = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)

    /** Un bilan par mois, du plus récent au plus ancien. */
    fun months(c: android.content.Context, trips: List<Trip>, charges: List<Charge>): List<Month> {
        val price = PriceSettings.pricePerBatteryKwh(c)
        val fuel = PriceSettings.fuelPrice(c) * PriceSettings.fuelPer100(c) / 100f
        val tripsBy = trips.groupBy { monthKey.format(Date(it.start)) }
        val chargesBy = charges.groupBy { monthKey.format(Date(it.end)) }
        return (tripsBy.keys + chargesBy.keys).sortedDescending().map { key ->
            val ts = tripsBy[key].orEmpty()
            val cs = chargesBy[key].orEmpty()
            val measured = ts.filter { kwh(it) != null }
            val kwh = measured.mapNotNull { kwh(it) }.sum()
            val measuredKm = measured.sumOf { it.distanceKm }
            val first = ts.firstOrNull()?.start ?: cs.first().end
            Month(
                label = monthName.format(Date(first)).replaceFirstChar { it.uppercase() },
                trips = ts.size,
                km = ts.sumOf { it.distanceKm },
                measuredKm = measuredKm,
                kwh = kwh,
                tripCost = kwh * price,
                charges = cs.size,
                chargedKwh = cs.sumOf { it.kwhAdded.toDouble() }.toFloat(),
                chargeCost = cs.sumOf { it.cost.toDouble() }.toFloat(),
                fuelCost = (measuredKm * fuel).toFloat(),
            )
        }
    }

    /** Ligne courte pour la voiture : « 820 km · 150 kWh · 35 € · 80 € économisés ». */
    fun monthLine(m: Month): String {
        val parts = mutableListOf(km(m.km))
        if (m.kwh > 0) {
            parts += String.format(Locale.FRANCE, "%.0f kWh", m.kwh)
            parts += euros(m.tripCost)
            if (m.saving > 0) parts += euros(m.saving) + " économisés"
        }
        return parts.joinToString(" · ")
    }

    private val chargeHour = SimpleDateFormat("EEE d MMM HH:mm", Locale.FRANCE)

    /** « 45 → 80 % · 30,5 kWh · 6,10 € ». */
    fun charge(ch: Charge) = String.format(
        Locale.FRANCE, "%.0f → %.0f %% · %.1f kWh · ", ch.pctFrom, ch.pctTo, ch.kwhAdded,
    ) + euros(ch.cost)

    /** « entre lun. 26 oct. 19:05 et mar. 27 oct. 07:30 », ou une seule heure si la recharge a été suivie. */
    fun chargeWhen(ch: Charge): String {
        val a = chargeHour.format(Date(ch.start))
        val b = chargeHour.format(Date(ch.end))
        return if (ch.end - ch.start < 30 * 60_000L) b else "entre $a et $b"
    }

    /** Trajet en cours : « 12,3 km · 25 min · −4 % ». */
    fun current(): String {
        val min = (System.currentTimeMillis() - TripRecorder.startTime) / 60_000
        val used = TripRecorder.batteryAtStart?.let { s -> CarSnapshot.batteryPercent?.let { s - it } }
        val batt = used?.takeIf { it >= 0.5f }?.let { String.format(Locale.FRANCE, " · −%.0f %% batterie", it) } ?: ""
        return km(TripRecorder.distanceKm) + " · " + duration(min) + batt
    }
}
