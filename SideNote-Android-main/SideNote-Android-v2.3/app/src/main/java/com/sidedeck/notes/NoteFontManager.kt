package com.sidedeck.notes

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Manages typography selection for notes on Android, providing 5 curated font styles
 * (Roboto Clean Sans, Cute Cursive Handwriting, Playful Rounded Casual, Literary Editorial Serif, Monospace Typewriter).
 */
object NoteFontManager {
    const val FONT_ROBOTO = "roboto"
    const val FONT_CURSIVE = "cursive"
    const val FONT_CASUAL = "casual"
    const val FONT_SERIF = "serif"
    const val FONT_MONOSPACE = "monospace"

    data class FontItem(
        val id: String,
        val displayName: String,
        val category: String,
        val familyName: String,
        val sample: String
    )

    val FONTS = listOf(
        FontItem(FONT_ROBOTO, "Roboto", "Android Default (Clean)", "sans-serif", "Aa Bb 123 • Clean"),
        FontItem(FONT_CURSIVE, "Caveat / Cursive", "Cute Handwritten ✨", "cursive", "Sticky Note Handwriting 🌸"),
        FontItem(FONT_CASUAL, "Nunito / Casual", "Playful & Rounded 🌸", "casual", "Soft & Friendly Notes"),
        FontItem(FONT_SERIF, "Lora / Serif", "Literary Editorial 🖋️", "serif", "Journal Reflections & Quotes"),
        FontItem(FONT_MONOSPACE, "JetBrains Mono", "Typewriter / Code", "monospace", "const note = true;")
    )

    fun getTypeface(context: Context, fontId: String, bold: Boolean = false): Typeface {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        return when (fontId.lowercase()) {
            FONT_CURSIVE -> Typeface.create("cursive", style)
            FONT_CASUAL -> Typeface.create("casual", style)
            FONT_SERIF, "lora" -> Typeface.create("serif", style)
            FONT_MONOSPACE, "mono" -> Typeface.create("monospace", style)
            else -> Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
    }

    fun getFontForNote(context: Context, noteId: String): String {
        val prefs = context.getSharedPreferences("sidenote_note_fonts", Context.MODE_PRIVATE)
        return prefs.getString("font_$noteId", getDefaultFont(context)) ?: FONT_ROBOTO
    }

    fun setFontForNote(context: Context, noteId: String, fontId: String) {
        val prefs = context.getSharedPreferences("sidenote_note_fonts", Context.MODE_PRIVATE)
        prefs.edit().putString("font_$noteId", fontId).apply()
    }

    fun getDefaultFont(context: Context): String {
        val prefs = context.getSharedPreferences("sidenote_note_fonts", Context.MODE_PRIVATE)
        return prefs.getString("default_note_font", FONT_ROBOTO) ?: FONT_ROBOTO
    }

    fun setDefaultFont(context: Context, fontId: String) {
        val prefs = context.getSharedPreferences("sidenote_note_fonts", Context.MODE_PRIVATE)
        prefs.edit().putString("default_note_font", fontId).apply()
    }

    /**
     * Shows a beautiful bottom-sheet modal dialog with live preview samples in each font.
     */
    fun showFontPickerDialog(context: Context, currentFontId: String, onFontSelected: (String) -> Unit) {
        Ui.configure(context)
        val dialog = Dialog(context)
        
        val scrollRoot = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val container = Ui.column(context).apply {
            background = Ui.bg(Ui.card, Ui.dp(context, 24).toFloat())
            setPadding(Ui.dp(context, 22), Ui.dp(context, 18), Ui.dp(context, 22), Ui.dp(context, 22))
        }
        scrollRoot.addView(container)

        // Drag handle indicator
        val bar = View(context).apply { background = Ui.bg(Ui.border, Ui.dp(context, 3).toFloat()) }
        val wrap = Ui.row(context).apply { gravity = Gravity.CENTER }
        wrap.addView(bar, Ui.lp(context, 36, 4))
        container.addView(wrap)
        container.addView(Ui.space(context, 14))

        // Title & Description
        val headerRow = Ui.row(context).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(Ui.text(context, "Choose Note Font", 18f, true, Ui.ink))
        container.addView(headerRow)

        val desc = Ui.text(context, "Select a font style for your sticky note:", 12.5f, false, Ui.muted)
        container.addView(desc)
        container.addView(Ui.space(context, 14))

        // Font options list
        FONTS.forEach { fontItem ->
            val isSelected = fontItem.id.equals(currentFontId, ignoreCase = true)
            val fontTypeface = getTypeface(context, fontItem.id, bold = false)
            val fontTypefaceBold = getTypeface(context, fontItem.id, bold = true)

            val itemCard = Ui.column(context).apply {
                val strokeColor = if (isSelected) Ui.violet else Ui.border
                val bgColor = if (isSelected) Ui.accentSoft else Ui.field
                background = Ui.bg(bgColor, Ui.dp(context, 14).toFloat(), strokeColor)
                setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 10))
                isClickable = true
                isFocusable = true
            }

            val topRow = Ui.row(context).apply { gravity = Gravity.CENTER_VERTICAL }

            val titleText = TextView(context).apply {
                text = fontItem.displayName
                textSize = 15f
                setTextColor(if (isSelected) Ui.violet else Ui.ink)
                typeface = fontTypefaceBold
            }
            topRow.addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))

            if (isSelected) {
                val checkView = ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(Ui.violet)
                }
                topRow.addView(checkView, Ui.lp(context, 18, 18))
            }

            itemCard.addView(topRow)

            val categoryText = TextView(context).apply {
                text = fontItem.category
                textSize = 11f
                setTextColor(Ui.muted)
                setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 4))
            }
            itemCard.addView(categoryText)

            val sampleText = TextView(context).apply {
                text = fontItem.sample
                textSize = if (fontItem.id == FONT_CURSIVE) 16f else 13f
                setTextColor(if (isSelected) Ui.ink else Ui.muted)
                typeface = fontTypeface
            }
            itemCard.addView(sampleText)

            itemCard.setOnClickListener {
                onFontSelected(fontItem.id)
                dialog.dismiss()
            }

            container.addView(itemCard)
            container.addView(Ui.space(context, 8))
        }

        // Close button
        val closeBtn = Ui.secondary(context, "Close") {
            dialog.dismiss()
        }
        container.addView(Ui.space(context, 6))
        container.addView(closeBtn)

        dialog.setContentView(scrollRoot)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }
}
