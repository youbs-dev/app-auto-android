package fr.brice.scenictrajets

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

private const val TAG = "ScenicTrajet"
private const val PREFS = "reglages"
private const val KEY_AUTO = "auto"

/**
 * Détection de la conduite par le téléphone (reconnaissance d'activité de Google) :
 * réveille l'app quand on monte en voiture, même si elle est fermée.
 */
object DrivingDetector {

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
        if (enabled) register(context) else unregister(context)
    }

    fun hasActivityPermission(context: Context) =
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun hasBackgroundLocation(context: Context) =
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, DrivingReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    fun register(context: Context) {
        if (!isEnabled(context) || !hasActivityPermission(context)) return
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
        )
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
                .addOnSuccessListener { Log.i(TAG, "Détection de conduite active") }
                .addOnFailureListener { Log.e(TAG, "Détection de conduite impossible", it) }
        } catch (e: Exception) {
            Log.e(TAG, "Détection de conduite impossible", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context)) }
    }
}

/** Reçoit « en voiture » de Google, et le redémarrage du téléphone ou une mise à jour de l'app. */
class DrivingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DrivingDetector.register(context)
                SyncWorker.schedule(context)
            }
            else -> {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val result = ActivityTransitionResult.extractResult(intent) ?: return
                val inCar = result.transitionEvents.any {
                    it.activityType == DetectedActivity.IN_VEHICLE &&
                        it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                }
                Log.i(TAG, "Activité détectée, en voiture : $inCar")
                if (inCar && DrivingDetector.isEnabled(context) && DrivingDetector.hasBackgroundLocation(context)) {
                    TripService.send(context, TripService.ACTION_WATCH)
                }
            }
        }
    }
}
