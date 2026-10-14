package com.sidedeck.notes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Auto-starts the floating sidebar after device reboot if overlay permission was previously granted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            // Restore all scheduled alarms across device reboots
            ReminderScheduler.rescheduleAll(context)

            if (Settings.canDrawOverlays(context)) {
                val svc = Intent(context, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            }
        }
    }
}
