package fr.brice.scenictrajets

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Point d'entrée lancé par Android Auto. */
class TestCarAppService : CarAppService() {

    // Version de test : accepte n'importe quel hôte (Android Auto en mode « sources inconnues »).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            // App ouverte dans la voiture : on surveille le départ pour démarrer le trajet tout seul.
            if (DrivingDetector.isEnabled(carContext)) TripService.send(carContext, TripService.ACTION_WATCH)
            val home = HomeScreen(carContext)
            // Ouverte depuis l'alerte batterie basse : on affiche directement les bornes.
            if (intent.getStringExtra(BatteryAlert.EXTRA_SCREEN) == BatteryAlert.SCREEN_CHARGERS) {
                carContext.getCarService(ScreenManager::class.java).push(home)
                return ChargersScreen(carContext)
            }
            return home
        }

        override fun onNewIntent(intent: Intent) {
            if (intent.getStringExtra(BatteryAlert.EXTRA_SCREEN) == BatteryAlert.SCREEN_CHARGERS) {
                carContext.getCarService(ScreenManager::class.java).push(ChargersScreen(carContext))
            }
        }
    }
}
