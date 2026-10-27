package fr.brice.scenictrajets

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/** Les derniers trajets, consultables dans la voiture. */
class TripsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val trips = runCatching { TripRecorder.trips(carContext) }.getOrDefault(emptyList())
        // La voiture limite le nombre de lignes (moins pendant la conduite).
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)

        val list = ItemList.Builder().setNoItemsMessage("Aucun trajet pour l'instant")
        if (trips.isNotEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle("Cette semaine : " + TripStats.total(trips, TripStats.startOfWeek()))
                    .addText("Ce mois : " + TripStats.total(trips, TripStats.startOfMonth()))
                    .build()
            )
            trips.take((limit - 1).coerceAtLeast(1)).forEach { t ->
                list.addItem(
                    Row.Builder()
                        .setTitle(TripStats.shortDate(t) + " · " + TripStats.hours(t))
                        .addText(TripStats.summary(t))
                        .addText(TripStats.battery(t))
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("Derniers trajets")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
