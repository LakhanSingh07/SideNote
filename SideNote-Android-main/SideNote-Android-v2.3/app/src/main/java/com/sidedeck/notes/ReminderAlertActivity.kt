package com.sidedeck.notes

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ReminderAlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.configure(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val noteId = intent.getStringExtra("reminder_note_id").orEmpty()
        val note = runCatching { NotesStore(this).all().firstOrNull { it.id == noteId } }.getOrNull()
        if (note == null) {
            finish()
            return
        }

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notifManager?.cancel(note.id.hashCode())

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(160, 15, 15, 18))
            setPadding(Ui.dp(this@ReminderAlertActivity, 22), Ui.dp(this@ReminderAlertActivity, 30),
                Ui.dp(this@ReminderAlertActivity, 22), Ui.dp(this@ReminderAlertActivity, 30))
        }

        val colorIdx = note.color.coerceIn(0, 5)
        val card = Ui.column(this).apply {
            background = Ui.bg(Ui.colors[colorIdx], Ui.dp(this@ReminderAlertActivity, 24).toFloat(), Ui.noteBorders[colorIdx])
            elevation = Ui.dp(this@ReminderAlertActivity, 16).toFloat()
            setPadding(Ui.dp(this@ReminderAlertActivity, 20), Ui.dp(this@ReminderAlertActivity, 20),
                Ui.dp(this@ReminderAlertActivity, 20), Ui.dp(this@ReminderAlertActivity, 18))
        }

        // Header: Badge + Alarm icon
        val headRow = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val alarmBadge = Ui.text(this, "⏰  REMINDER", 11.5f, true, Ui.violet).apply {
            background = Ui.bg(Color.argb(40, 138, 75, 255), Ui.dp(this@ReminderAlertActivity, 10).toFloat())
            setPadding(Ui.dp(this@ReminderAlertActivity, 10), Ui.dp(this@ReminderAlertActivity, 4),
                Ui.dp(this@ReminderAlertActivity, 10), Ui.dp(this@ReminderAlertActivity, 4))
        }
        headRow.addView(alarmBadge)
        headRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Ui.ink)
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }
        headRow.addView(closeBtn, LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)))
        card.addView(headRow)
        card.addView(Ui.space(this, 14))

        // Title
        val titleTv = TextView(this).apply {
            text = note.title.ifBlank { "SideNote Reminder" }
            textSize = 20f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(Ui.ink)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(titleTv)
        card.addView(Ui.space(this, 10))

        // Body content inside scrollview if long
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val bodyTv = TextView(this).apply {
            text = if (note.body.isBlank()) "No additional details." else RichTextFormatter.fromStorageString(note.body)
            textSize = 14f
            setTextColor(if (Ui.dark) Ui.ink else Color.rgb(55, 58, 60))
            setLineSpacing(Ui.dp(this@ReminderAlertActivity, 3).toFloat(), 1f)
        }
        scroll.addView(bodyTv)
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        card.addView(Ui.space(this, 20))

        // Action buttons: Snooze 10m, Open Note, Dismiss
        val actionsRow = Ui.row(this)
        val snoozeBtn = Ui.secondary(this, "Snooze 10m") {
            val snoozeAt = System.currentTimeMillis() + 10 * 60 * 1000L
            val updated = note.copy(reminderAt = snoozeAt)
            runCatching {
                NotesStore(this).save(updated)
                ReminderScheduler.schedule(this, updated)
            }
            Toast.makeText(this, "Snoozed for 10 minutes", Toast.LENGTH_SHORT).show()
            finish()
        }
        actionsRow.addView(snoozeBtn, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f).apply { marginEnd = Ui.dp(this@ReminderAlertActivity, 8) })

        val openBtn = Ui.button(this, "Open Note") {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("open_note_id", note.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(openIntent)
            finish()
        }
        actionsRow.addView(openBtn, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1.2f))
        card.addView(actionsRow)

        val cardLp = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)
        root.addView(card, cardLp)
        setContentView(root)
    }
}
