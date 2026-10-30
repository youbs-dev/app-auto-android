package fr.brice.scenictrajets

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Ce que la voiture transmet, ligne par ligne, et les autorisations. Utile pour dépanner. */
class DiagnosticScreen(carContext: CarContext, private val home: HomeScreen) : Screen(carContext) {

    private val car get() = home.car
    private var permissionError: String? = null

    init {
        // Rafraîchit cet écran quand l'accueil reçoit de nouvelles valeurs.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            private val tick = object : Runnable {
                override fun run() { invalidate(); handler.postDelayed(this, 5_000) }
            }
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            override fun onStart(owner: LifecycleOwner) { handler.postDelayed(tick, 5_000) }
            override fun onStop(owner: LifecycleOwner) = handler.removeCallbacks(tick)
        })
    }

    override fun onGetTemplate(): Template {
        val missing = car.missingPermissions()
        val permRow = Row.Builder()
            .setTitle("Autorisations")
            .addText(
                when {
                    missing.isEmpty() -> "toutes accordées"
                    permissionError != null -> "erreur : $permissionError"
                    else -> "${missing.size} manquante(s) : toucher pour demander (à l'arrêt)"
                }
            )
        if (missing.isNotEmpty()) {
            permRow.setOnClickListener(ParkedOnlyOnClickListener.create { requestCarPermissions(missing) })
        }

        val version = runCatching {
            carContext.packageManager.getPackageInfo(carContext.packageName, 0).versionName
        }.getOrNull() ?: "?"

        val list = ItemList.Builder()
            .addItem(row("Véhicule", car.vehicule))
            .addItem(row("Batterie · autonomie", car.batterie + " · " + car.autonomie))
            .addItem(row("Kilométrage", car.kilometrage))
            .addItem(row("Vitesse (voiture)", car.vitesse))
            .addItem(permRow.build())
            .addItem(row("Version de l'app", version))
            .addItem(row("Dernier plantage", CrashLog.summary(carContext) ?: "aucun"))
            .build()

        return ListTemplate.Builder()
            .setTitle("Diagnostic")
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            // Une seule action avec texte est permise dans cette barre.
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Toutes les données")
                            .setOnClickListener { screenManager.push(AllDataScreen(carContext)) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /** Demande les autorisations depuis la voiture ; en cas d'échec, l'erreur s'affiche au lieu de planter. */
    private fun requestCarPermissions(missing: List<String>) {
        try {
            carContext.requestPermissions(missing) { granted, rejected ->
                CarToast.makeText(
                    carContext,
                    "Accordées : ${granted.size}, refusées : ${rejected.size}",
                    CarToast.LENGTH_LONG,
                ).show()
                car.restart()
                invalidate()
            }
        } catch (e: Exception) {
            Log.e("ScenicTest", "Demande d'autorisations impossible", e)
            permissionError = e.javaClass.simpleName + (e.message?.let { " : $it" } ?: "")
            CarToast.makeText(
                carContext,
                "Ouvre l'app Scénic Trajets sur le téléphone pour autoriser l'accès",
                CarToast.LENGTH_LONG,
            ).show()
            invalidate()
        }
    }

    private fun row(title: String, value: String) =
        Row.Builder().setTitle(title).addText(value).build()
}
