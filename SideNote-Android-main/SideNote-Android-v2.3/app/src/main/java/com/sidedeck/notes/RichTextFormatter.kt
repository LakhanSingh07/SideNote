package com.sidedeck.notes

import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.EditText

/**
 * Native Android Rich Text (WYSIWYG) engine:
 * Applies visual Bold, Italic, Underline, and Heading spans directly inside EditText
 * instead of inserting raw markdown markup characters like '**' or '_'.
 */
object RichTextFormatter {

    fun applyBold(editText: EditText) {
        toggleStyleSpan(editText, Typeface.BOLD)
    }

    fun applyItalic(editText: EditText) {
        toggleStyleSpan(editText, Typeface.ITALIC)
    }

    fun applyUnderline(editText: EditText) {
        val s = editText.selectionStart.coerceAtLeast(0)
        val e = editText.selectionEnd.coerceAtLeast(0)
        val editable = editText.text as? Editable ?: return

        val range = if (s < e) s to e else getWordRange(editText.text, s)
        val start = range.first
        val end = range.second

        if (start < end) {
            val existing = editable.getSpans(start, end, UnderlineSpan::class.java)
            if (existing.isNotEmpty()) {
                existing.forEach { editable.removeSpan(it) }
            } else {
                editable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun applyHeading(editText: EditText) {
        val editable = editText.text as? Editable ?: return
        val cursor = editText.selectionStart.coerceAtLeast(0)
        val content = editable.toString()

        val lineStart = content.lastIndexOf('\n', cursor - 1) + 1
        val lineEnd = content.indexOf('\n', cursor).let { if (it == -1) content.length else it }

        if (lineStart <= lineEnd) {
            val sizeSpans = editable.getSpans(lineStart, lineEnd, RelativeSizeSpan::class.java)
            if (sizeSpans.isNotEmpty()) {
                sizeSpans.forEach { editable.removeSpan(it) }
                editable.getSpans(lineStart, lineEnd, StyleSpan::class.java)
                    .filter { it.style == Typeface.BOLD }
                    .forEach { editable.removeSpan(it) }
            } else {
                editable.setSpan(RelativeSizeSpan(1.3f), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(StyleSpan(Typeface.BOLD), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun toggleStyleSpan(editText: EditText, style: Int) {
        val s = editText.selectionStart.coerceAtLeast(0)
        val e = editText.selectionEnd.coerceAtLeast(0)
        val editable = editText.text as? Editable ?: return

        val range = if (s < e) s to e else getWordRange(editText.text, s)
        val start = range.first
        val end = range.second

        if (start < end) {
            val existing = editable.getSpans(start, end, StyleSpan::class.java).filter { it.style == style }
            if (existing.isNotEmpty()) {
                existing.forEach { editable.removeSpan(it) }
            } else {
                editable.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun getWordRange(text: CharSequence, cursor: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        var start = cursor.coerceIn(0, text.length)
        var end = cursor.coerceIn(0, text.length)

        while (start > 0 && !text[start - 1].isWhitespace()) start--
        while (end < text.length && !text[end].isWhitespace()) end++

        return start to end
    }

    /**
     * Converts formatted text to clean string for storage.
     * Uses HTML format if styling spans exist; otherwise saves clean plain text.
     */
    fun toStorageString(text: CharSequence): String {
        if (text !is Spanned) return text.toString()
        val spans = text.getSpans(0, text.length, CharacterStyle::class.java)
        if (spans.isEmpty()) return text.toString()

        val rawHtml = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
        } else {
            @Suppress("DEPRECATION")
            Html.toHtml(text)
        }
        return rawHtml.trim()
    }

    /**
     * Converts stored string into rich Spanned for editing or display.
     * Supports both HTML tags and legacy markdown symbols (**bold**, _italic_, # Header).
     */
    fun fromStorageString(stored: String): CharSequence {
        if (stored.isBlank()) return ""

        if (stored.contains("<") && stored.contains(">")) {
            val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(stored, Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(stored)
            }
            var len = spanned.length
            while (len > 0 && spanned[len - 1] == '\n') len--
            return SpannableStringBuilder(spanned.subSequence(0, len))
        }

        // Convert legacy markdown into styled spans if present
        if (stored.contains("**") || stored.contains("_") || stored.startsWith("#")) {
            return parseLegacyMarkdown(stored)
        }

        return SpannableStringBuilder(stored)
    }

    fun toPlainText(stored: String): String {
        return fromStorageString(stored).toString()
    }

    private fun parseLegacyMarkdown(raw: String): CharSequence {
        val ssb = SpannableStringBuilder()
        val lines = raw.lines()

        lines.forEachIndexed { index, line ->
            var lineContent = line
            var isHeading = false

            if (lineContent.startsWith("# ")) {
                isHeading = true
                lineContent = lineContent.removePrefix("# ")
            }

            val lineStart = ssb.length

            // Parse **bold** and _italic_
            var i = 0
            while (i < lineContent.length) {
                if (lineContent.startsWith("**", i)) {
                    val close = lineContent.indexOf("**", i + 2)
                    if (close != -1) {
                        val word = lineContent.substring(i + 2, close)
                        val spanStart = ssb.length
                        ssb.append(word)
                        ssb.setSpan(StyleSpan(Typeface.BOLD), spanStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 2
                        continue
                    }
                } else if (lineContent[i] == '_' && (i == 0 || lineContent[i - 1].isWhitespace())) {
                    val close = lineContent.indexOf('_', i + 1)
                    if (close != -1 && close > i + 1) {
                        val word = lineContent.substring(i + 1, close)
                        val spanStart = ssb.length
                        ssb.append(word)
                        ssb.setSpan(StyleSpan(Typeface.ITALIC), spanStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 1
                        continue
                    }
                }
                ssb.append(lineContent[i])
                i++
            }

            if (isHeading) {
                ssb.setSpan(RelativeSizeSpan(1.3f), lineStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(StyleSpan(Typeface.BOLD), lineStart, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (index < lines.size - 1) ssb.append("\n")
        }

        return ssb
    }
}
