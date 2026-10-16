package com.sidedeck.notes
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils

/** Pairing is durable; reachability is measured, never inferred from a saved token. */
object SyncHealth {
    const val ACTION="com.sidedeck.notes.SYNC_HEALTH"
    fun update(c:Context,state:String,error:String=""){
        c.getSharedPreferences("sync_health",0).edit().putString("state",state).putString("error",error.take(300)).putLong("state_at",System.currentTimeMillis()).apply()
        c.sendBroadcast(Intent(ACTION).setPackage(c.packageName))
    }
    fun contact(c:Context){c.getSharedPreferences("sync_health",0).edit().putLong("last_contact",System.currentTimeMillis()).apply()}
    fun label(c:Context):String {
        if(!LanSyncManager.isPaired(c))return "Connect a PC"
        val p=c.getSharedPreferences("sync_health",0);val age=System.currentTimeMillis()-p.getLong("state_at",0)
        if(p.getString("state","")=="syncing" && age in 0..120000)return "Syncing"
        if(p.getString("state","")=="offline")return "Offline"
        return if(System.currentTimeMillis()-p.getLong("last_contact",0) in 0..45000)"Online" else "Paired"
    }
    fun detail(c:Context):String {
        val time=LanSyncManager.getLastSyncTime(c)
        if(!LanSyncManager.isPaired(c))return "Scan the QR code on your Windows PC"
        return if(time==0L)"Waiting for the first sync" else "Last synced "+DateUtils.getRelativeTimeSpanString(time,System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS)
    }
}
