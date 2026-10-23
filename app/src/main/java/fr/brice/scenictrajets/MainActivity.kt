package fr.brice.scenictrajets

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val ANDROID_AUTO = "com.google.android.projection.gearhead"

/**
 * Écran affiché sur le téléphone : l'app elle-même tourne dans Android Auto,
 * cet écran sert seulement à la retrouver et à expliquer comment l'activer.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val message = TextView(this).apply {
            textSize = 17f
            text = """
                L'app est bien installée ✅

                Elle s'affiche sur l'écran du Scénic, pas sur le téléphone.

                Pour la voir dans la voiture :
                1. Ouvre les paramètres d'Android Auto (bouton ci-dessous).
                2. Touche 10 fois « Version » tout en bas pour activer le mode développeur.
                3. Menu ⋮ > Paramètres pour les développeurs > coche « Sources inconnues ».
                4. Branche le téléphone : « Scénic Trajets » apparaît dans le lanceur d'apps d'Android Auto (l'icône en grille).
                5. Dans l'app, touche « Permissions » pour autoriser la lecture des données de la voiture.
            """.trimIndent()
        }

        val button = Button(this).apply {
            text = "Ouvrir les paramètres d'Android Auto"
            setOnClickListener { openAndroidAutoSettings() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(message)
            addView(button)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun openAndroidAutoSettings() {
        val intent = packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO"))
        runCatching { startActivity(intent) }
    }
}
