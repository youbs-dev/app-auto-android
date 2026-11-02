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

/** Écran « Infos » : ce que la voiture transmet et les autorisations. */
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
                    missing.isEmpty() -> "Toutes accordées"
                    permissionError != null -> "$permissionError"
                    else -> if (missing.size == 1) "1 manquante · touche pour l'accorder (à l'arrêt)" else "${missing.size} manquantes · touche pour les accorder (à l'arrêt)"
                }
            )
        if (missing.isNotEmpty()) {
            permRow.setOnClickListener(ParkedOnlyOnClickListener.create { requestCarPermissions(missing) })
        }

        val list = ItemList.Builder()
            .addItem(row("Véhicule", car.vehicule))
            .addItem(row("Batterie · autonomie", car.batterie + " · " + car.autonomie))
            .addItem(row("Kilométrage", car.kilometrage))
            .addItem(row("Vitesse (voiture)", car.vitesse))
            .addItem(permRow.build())
            .build()

        return ListTemplate.Builder()
            .setTitle("Infos")
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
                    if (rejected.isEmpty()) "Autorisations accordées" else "Certaines autorisations ont été refusées",
                    CarToast.LENGTH_LONG,
                ).show()
                car.restart()
                invalidate()
            }
        } catch (e: Exception) {
            Log.e("ScenicTrajets", "Demande d'autorisations impossible", e)
            permissionError = "À accorder depuis l'app sur le téléphone"
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
