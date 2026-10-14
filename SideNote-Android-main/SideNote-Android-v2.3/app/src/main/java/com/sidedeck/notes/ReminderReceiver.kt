package com.sidedeck.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("note_id") ?: return
        // Corrupt storage must not crash the broadcast receiver or trigger
        // an unrelated notification. The original notes remain untouched.
        val note = try {
            NotesStore(context).all().firstOrNull {
                it.id == id && it.reminderAt > 0 && it.reminderAt <= (System.currentTimeMillis() + 5000L)
            }
        } catch (_: IllegalStateException) { null } ?: return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sidenote_reminders_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()
            val channel = NotificationChannel(channelId, "Note Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts and sound notifications for scheduled note reminders"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                enableLights(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(channel)
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_note_id", id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val open = PendingIntent.getActivity(context, id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val alertIntent = Intent(context, ReminderAlertActivity::class.java).apply {
            putExtra("reminder_note_id", id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            id.hashCode() + 1,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notice = Notification.Builder(context, channelId).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(note.title.ifBlank { "SideNote reminder" })
            .setContentText(note.body.take(120))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(fullScreenPending, true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_MAX)
            .build()
        try { manager.notify(id.hashCode(), notice) } catch (_: SecurityException) { }

        // Launch on-screen dialog popup
        runCatching { context.startActivity(alertIntent) }

        // Also display floating overlay card if overlay permission is granted
        if (android.provider.Settings.canDrawOverlays(context)) {
            runCatching {
                val overlayIntent = Intent(context, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_REMINDER_ALERT
                    putExtra("reminder_note_id", id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent)
                } else {
                    context.startService(overlayIntent)
                }
            }
        }
    }
}

object ReminderScheduler {
    fun schedule(context: Context, note: Note) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(context,ReminderReceiver::class.java).putExtra("note_id",note.id)
        val pending = PendingIntent.getBroadcast(context,note.id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(pending)
        if (note.reminderAt > System.currentTimeMillis()) manager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,note.reminderAt,pending)
    }

    fun rescheduleAll(context: Context) {
        try {
            val store = NotesStore(context)
            val now = System.currentTimeMillis()
            store.all().forEach { note ->
                if (note.reminderAt > now) {
                    schedule(context, note)
                }
            }
        } catch (_: Exception) {}
    }
}
