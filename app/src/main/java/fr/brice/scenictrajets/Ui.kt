package fr.brice.scenictrajets

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Petits outils pour construire des écrans propres sans fichier de mise en page. */
object Ui {

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    private fun night(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun textColor(c: Context) = if (night(c)) Color.parseColor("#ECEDEF") else Color.parseColor("#1B1D21")
    fun secondaryColor(c: Context) = if (night(c)) Color.parseColor("#A2A7B0") else Color.parseColor("#5C6370")
    fun accent(c: Context) = if (night(c)) Color.parseColor("#6CB1FF") else Color.parseColor("#2E7DD7")
    private fun cardColor(c: Context) = if (night(c)) Color.parseColor("#1D1F24") else Color.WHITE

    /** Colonne principale de l'écran, avec des marges. */
    fun column(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 24))
    }

    /** Carte arrondie, comme dans les apps récentes. */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 16).toFloat()
            setColor(cardColor(c))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(c, 12) }
    }

    fun title(c: Context, text: CharSequence = "") = TextView(c).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor(c))
    }

    fun text(c: Context, text: CharSequence = "", size: Float = 15f) = TextView(c).apply {
        this.text = text
        textSize = size
        setTextColor(textColor(c))
        setLineSpacing(0f, 1.15f)
    }

    fun secondary(c: Context, text: CharSequence = "") = TextView(c).apply {
        this.text = text
        textSize = 14f
        setTextColor(secondaryColor(c))
        setLineSpacing(0f, 1.15f)
    }

    /** Intitulé de groupe (« Aujourd'hui », « Hier »…). */
    fun header(c: Context, text: CharSequence) = TextView(c).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent(c))
        setPadding(dp(c, 4), dp(c, 8), 0, dp(c, 6))
    }

    fun button(c: Context, text: CharSequence, primary: Boolean = false, onClick: () -> Unit) = Button(c).apply {
        this.text = text
        isAllCaps = false
        if (primary) {
            backgroundTintList = ColorStateList.valueOf(accent(c))
            setTextColor(if (night(c)) Color.parseColor("#0B1A2B") else Color.WHITE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(c, 8) }
    }

    fun View.show(visible: Boolean) { visibility = if (visible) View.VISIBLE else View.GONE }
}
