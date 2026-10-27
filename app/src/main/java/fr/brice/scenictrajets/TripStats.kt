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

    /** Trajet en cours : « 12,3 km · 25 min · −4 % ». */
    fun current(): String {
        val min = (System.currentTimeMillis() - TripRecorder.startTime) / 60_000
        val used = TripRecorder.batteryAtStart?.let { s -> CarSnapshot.batteryPercent?.let { s - it } }
        val batt = used?.takeIf { it >= 0.5f }?.let { String.format(Locale.FRANCE, " · −%.0f %% batterie", it) } ?: ""
        return km(TripRecorder.distanceKm) + " · " + duration(min) + batt
    }
}
