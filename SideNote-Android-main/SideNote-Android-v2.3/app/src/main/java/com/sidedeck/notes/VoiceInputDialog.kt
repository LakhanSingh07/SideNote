package com.sidedeck.notes

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class VoiceInputDialog(
    private val context: Context,
    private val initialLanguage: String = "auto",
    private val onTextConfirmed: (String) -> Unit
) {
    private var dialog: Dialog? = null
    private var voiceHelper: VoiceInputHelper? = null
    private var currentText = ""
    private var selectedLanguage = initialLanguage

    fun show() {
        val d = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog = d

        val root = Ui.column(context).apply {
            background = Ui.bg(Ui.card, Ui.dp(context, 24).toFloat(), Ui.border)
            elevation = Ui.dp(context, 16).toFloat()
            setPadding(Ui.dp(context, 20), Ui.dp(context, 18), Ui.dp(context, 20), Ui.dp(context, 20))
        }

        // Header
        val header = Ui.row(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.icon(context, R.drawable.ic_mic, 22, Ui.violet))
        header.addView(Ui.space(context, 8))
        header.addView(Ui.text(context, "Voice to Text", 17f, true, Ui.ink), LinearLayout.LayoutParams(0, -2, 1f))

        val closeBtn = Ui.icon(context, R.drawable.ic_close, 18, Ui.muted).apply {
            setPadding(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
            isClickable = true; isFocusable = true
            setOnClickListener {
                voiceHelper?.destroy()
                d.dismiss()
            }
        }
        header.addView(closeBtn)
        root.addView(header)
        root.addView(Ui.space(context, 14))

        // Language selector chips
        val langRow = Ui.row(context)
        val chips = listOf(
            "auto" to "Auto (Any)",
            "hi-IN" to "हिन्दी (Hindi)",
            "en-IN" to "English (IN)"
        )
        val chipViews = mutableListOf<TextView>()

        chips.forEach { (code, label) ->
            val isSelected = selectedLanguage.equals(code, ignoreCase = true)
            val chip = Ui.text(context, label, 12f, isSelected).apply {
                val bgCol = if (isSelected) Ui.accentSoft else Ui.field
                val strokeCol = if (isSelected) Ui.violet else Ui.border
                val textCol = if (isSelected) Ui.violet else Ui.ink
                background = Ui.bg(bgCol, Ui.dp(context, 12).toFloat(), strokeCol)
                setTextColor(textCol)
                setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    selectedLanguage = code
                    chipViews.forEachIndexed { idx, cv ->
                        val active = chips[idx].first == code
                        cv.background = Ui.bg(if (active) Ui.accentSoft else Ui.field, Ui.dp(context, 12).toFloat(), if (active) Ui.violet else Ui.border)
                        cv.setTextColor(if (active) Ui.violet else Ui.ink)
                    }
                    restartListening()
                }
            }
            chipViews.add(chip)
            langRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(context, 6) })
        }
        root.addView(langRow)
        root.addView(Ui.space(context, 16))

        // Visualizer Container (Mic button + Pulse wave)
        val micContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 90))
        }

        val pulseRing = View(context).apply {
            val ringRadius = Ui.dp(context, 35).toFloat()
            background = Ui.bg(Color.argb(40, 124, 77, 255), ringRadius)
            scaleX = 1f; scaleY = 1f
        }
        val pulseLp = FrameLayout.LayoutParams(Ui.dp(context, 70), Ui.dp(context, 70), Gravity.CENTER)
        micContainer.addView(pulseRing, pulseLp)

        val micButton = FrameLayout(context).apply {
            background = Ui.bg(Ui.violet, Ui.dp(context, 28).toFloat())
            elevation = Ui.dp(context, 4).toFloat()
            isClickable = true; isFocusable = true
            val icon = Ui.icon(context, R.drawable.ic_mic, 26, Color.WHITE)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            addView(icon, lp)
            setOnClickListener {
                if (voiceHelper?.isListening == true) {
                    voiceHelper?.stopListening()
                } else {
                    restartListening()
                }
            }
        }
        val micBtnLp = FrameLayout.LayoutParams(Ui.dp(context, 56), Ui.dp(context, 56), Gravity.CENTER)
        micContainer.addView(micButton, micBtnLp)
        root.addView(micContainer)

        // Status text
        val statusTv = Ui.text(context, "Listening… Speak now", 13f, false, Ui.violet).apply {
            gravity = Gravity.CENTER
        }
        root.addView(statusTv)
        root.addView(Ui.space(context, 10))

        // Live streaming transcript text box
        val textScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 110))
            background = Ui.bg(Ui.field, Ui.dp(context, 14).toFloat(), Ui.border)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        val transcriptTv = TextView(context).apply {
            textSize = 15f
            setTextColor(Ui.ink)
            setLineSpacing(Ui.dp(context, 3).toFloat(), 1f)
            hint = "Words appear here live as you speak…"
            setHintTextColor(Ui.muted)
        }
        textScroll.addView(transcriptTv)
        root.addView(textScroll)
        root.addView(Ui.space(context, 16))

        // Bottom Action buttons
        val btnRow = Ui.row(context).apply { gravity = Gravity.CENTER_VERTICAL }

        val cancelBtn = Ui.text(context, "Cancel", 14f, false, Ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10))
            isClickable = true; isFocusable = true
            setOnClickListener {
                voiceHelper?.destroy()
                d.dismiss()
            }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        val doneBtn = Ui.text(context, "Done / Insert", 14f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = Ui.bg(Ui.violet, Ui.dp(context, 14).toFloat())
            setPadding(Ui.dp(context, 20), Ui.dp(context, 10), Ui.dp(context, 20), Ui.dp(context, 10))
            isClickable = true; isFocusable = true
            setOnClickListener {
                val textToSubmit = currentText.ifBlank { transcriptTv.text.toString().trim() }
                voiceHelper?.destroy()
                d.dismiss()
                if (textToSubmit.isNotBlank()) {
                    onTextConfirmed(textToSubmit)
                }
            }
        }
        btnRow.addView(doneBtn)
        root.addView(btnRow)

        d.setContentView(root)
        d.setCancelable(true)
        d.setOnDismissListener {
            voiceHelper?.destroy()
        }

        // Window width constraint
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.92f).toInt().coerceAtMost(Ui.dp(context, 400))
        d.window?.setLayout(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        d.show()

        // Init Voice Helper
        voiceHelper = VoiceInputHelper(context, object : VoiceInputHelper.Callback {
            override fun onReady() {
                statusTv.text = "Listening… बोलिए…"
                statusTv.setTextColor(Ui.violet)
            }

            override fun onRmsChanged(rmsdB: Float) {
                val scale = 1f + (rmsdB.coerceIn(0f, 10f) / 10f) * 0.45f
                pulseRing.animate().scaleX(scale).scaleY(scale).setDuration(80).start()
            }

            override fun onPartialResult(text: String) {
                currentText = text
                transcriptTv.text = text
                textScroll.fullScroll(View.FOCUS_DOWN)
            }

            override fun onFinalResult(text: String) {
                if (text.isNotBlank()) {
                    currentText = text
                    transcriptTv.text = text
                }
                statusTv.text = "Finished listening"
                statusTv.setTextColor(Ui.ink)
                pulseRing.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }

            override fun onError(errorMsg: String) {
                statusTv.text = errorMsg
                statusTv.setTextColor(Color.rgb(210, 80, 80))
                pulseRing.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }

            override fun onStateChanged(isListening: Boolean) {
                micButton.background = Ui.bg(
                    if (isListening) Color.rgb(220, 50, 50) else Ui.violet,
                    Ui.dp(context, 28).toFloat()
                )
                if (!isListening) {
                    pulseRing.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
        })

        restartListening()
    }

    private fun restartListening() {
        voiceHelper?.startListening(selectedLanguage)
    }
}
