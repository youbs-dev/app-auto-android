package fr.brice.scenictrajets

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/** Icônes dessinées (Material) pour l'écran de la voiture, teintées aux couleurs d'Android Auto. */
object CarIcons {
    fun of(c: CarContext, @DrawableRes res: Int, tint: CarColor = CarColor.DEFAULT): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(c, res)).setTint(tint).build()
}
