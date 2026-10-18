package fr.brice.scenictrajets

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Point d'entrée lancé par Android Auto. */
class TestCarAppService : CarAppService() {

    // Version de test : accepte n'importe quel hôte (Android Auto en mode « sources inconnues »).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = VehicleDataScreen(carContext)
    }
}
