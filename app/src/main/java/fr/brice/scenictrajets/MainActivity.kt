package fr.brice.scenictrajets

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val ANDROID_AUTO = "com.google.android.projection.gearhead"
private const val REQUEST_CAR_PERMISSIONS = 1

/**
 * Écran affiché sur le téléphone : l'app tourne dans Android Auto,
 * cet écran sert à accorder l'accès aux données de la voiture et à retrouver l'app.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 18f }

        val askButton = Button(this).apply {
            text = "Autoriser l'accès aux données de la voiture"
            setOnClickListener { askCarPermissions() }
        }

        val settingsButton = Button(this).apply {
            text = "Ouvrir les autorisations de l'app"
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            }
        }

        val help = TextView(this).apply {
            textSize = 15f
            text = """

                L'app s'affiche sur l'écran du Scénic, via Android Auto.
                Les autorisations ci-dessus lui permettent de lire la batterie, l'autonomie, la vitesse et le kilométrage.
            """.trimIndent()
        }

        val autoButton = Button(this).apply {
            text = "Ouvrir les paramètres d'Android Auto"
            setOnClickListener { openAndroidAutoSettings() }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(askButton)
            addView(settingsButton)
            addView(help)
            addView(autoButton)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun missingPermissions() = CAR_PERMISSIONS.filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun refreshStatus() {
        val missing = missingPermissions()
        status.text = if (missing.isEmpty()) {
            "✅ Accès aux données de la voiture : autorisé"
        } else {
            "⚠️ Accès aux données de la voiture : ${missing.size} autorisation(s) manquante(s)"
        }
    }

    private fun askCarPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_CAR_PERMISSIONS)
        else refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun openAndroidAutoSettings() {
        val intent = packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO"))
        runCatching { startActivity(intent) }
    }
}
