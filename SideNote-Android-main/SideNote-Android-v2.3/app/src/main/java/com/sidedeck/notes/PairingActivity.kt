package com.sidedeck.notes

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode

/** QR invites bind the address, one-time secret and TLS identity in one gesture. */
class PairingActivity:Activity(){
    private lateinit var status:TextView
    private lateinit var nearby:LinearLayout
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var working=false
    override fun onCreate(state:Bundle?){
        super.onCreate(state);Ui.configure(this)
        val root=Ui.column(this).apply{setPadding(Ui.dp(this@PairingActivity,20),Ui.dp(this@PairingActivity,20),Ui.dp(this@PairingActivity,20),Ui.dp(this@PairingActivity,20));setBackgroundColor(Ui.surface)}
        root.fitsSystemWindows=true
        val scroll=ScrollView(this).apply{addView(root)};setContentView(scroll)
        root.addView(Ui.outline(this,"Back"){finish()});root.addView(Ui.space(this,24))
        root.addView(Ui.editorial(this,"Better together.",32f));root.addView(Ui.space(this,10))
        root.addView(Ui.text(this,"Your notes and files, on the PC you choose. Both devices need the same local network.",14f,false,Ui.muted));root.addView(Ui.space(this,22))
        root.addView(Ui.button(this,"Scan PC's QR code"){
            if(!working)GmsBarcodeScanning.getClient(this,GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()).startScan()
                .addOnSuccessListener {it.rawValue?.let(::acceptCode)}.addOnFailureListener {message("Scanner unavailable. Use Paste setup code below.")}
        });root.addView(Ui.space(this,10))
        root.addView(Ui.outline(this,"Paste setup code"){
            val field=EditText(this).apply{hint="Copy setup code from SideNote on your PC";setTextColor(Ui.ink);setHintTextColor(Ui.muted)}
            AlertDialog.Builder(this).setTitle("Secure pairing code").setView(field).setNegativeButton("Cancel",null).setPositiveButton("Connect"){_,_->acceptCode(field.text.toString().trim())}.show()
        });root.addView(Ui.space(this,18))
        status=Ui.text(this,"Open Windows SideNote → Sync & Devices → Pair a phone.",13f,false,Ui.muted).apply{accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE};root.addView(status)
        root.addView(Ui.space(this,24));root.addView(Ui.text(this,"Saved PCs",18f,true));root.addView(Ui.space(this,10))
        val saved=SecureLan.savedPeers(this)
        if(saved.isEmpty())root.addView(Ui.text(this,"Your paired computers will appear here.",13f,false,Ui.muted))
        for(peer in saved){
            val card=Ui.surfaceCard(this);val active=getSharedPreferences("sidenote_sync",0).getString("paired_device_id",null)==peer.getString("id")
            card.addView(Ui.text(this,peer.getString("name"),16f,true));card.addView(Ui.text(this,if(active)SyncHealth.label(this)+" · "+SyncHealth.detail(this) else "Saved · Tap to switch",12f,false,Ui.muted));card.addView(Ui.space(this,10))
            val actions=Ui.row(this)
            actions.addView(Ui.secondary(this,if(active)"Sync now" else "Switch here"){
                if(!working){SecureLan.selectPeer(this,peer.getString("id"));message("Selected ${peer.getString("name")}. Notes stay on every device.")}
            },LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(Ui.outline(this,"Forget"){
                AlertDialog.Builder(this).setTitle("Forget this PC?").setMessage("Your notes and saved files stay. If this PC is offline, also remove the phone from its Sync & Devices screen to revoke access there.")
                    .setNegativeButton("Keep",null).setPositiveButton("Forget"){_,_->SecureLan.forgetPeer(this,peer.getString("id"));recreate()}.show()
            });card.addView(actions);root.addView(card);root.addView(Ui.space(this,10))
        }
        root.addView(Ui.space(this,20));root.addView(Ui.text(this,"Nearby PCs",18f,true));nearby=Ui.column(this);root.addView(nearby)
        root.addView(Ui.outline(this,"Find devices again"){scan()});scan()
    }
    private fun message(text:String){status.text=text}
    private fun scan(){
        nearby.removeAllViews();nearby.addView(Ui.text(this,"Looking on your Wi-Fi…",13f,false,Ui.muted))
        PcDiscovery.scan(this){pcs->if(isFinishing||isDestroyed)return@scan
            nearby.removeAllViews();if(pcs.isEmpty())nearby.addView(Ui.text(this,"No PC found yet. QR pairing also works when discovery is blocked.",13f,false,Ui.muted))
            for(pc in pcs)nearby.addView(Ui.outline(this,pc.name){manual(pc)})
        }
    }
    private fun manual(pc:PcDiscovery.Pc){
        if(working)return
        if(!pc.fingerprint.matches(Regex("[a-f0-9]{64}"))){message("Update SideNote on this PC to enable secure pairing.");return}
        val layout=Ui.column(this).apply{setPadding(40,24,40,24)}
        layout.addView(Ui.text(this,"Compare this security fingerprint with the one shown in Windows SideNote. Only continue if they match.",13f,false,Ui.muted))
        layout.addView(Ui.text(this,pc.fingerprint.chunked(4).joinToString(" "),12f,true))
        val pin=EditText(this).apply{hint="4-digit PIN on this PC";inputType=android.text.InputType.TYPE_CLASS_NUMBER;filters=arrayOf(android.text.InputFilter.LengthFilter(4));setTextColor(Ui.ink)}
        layout.addView(pin)
        AlertDialog.Builder(this).setTitle(pc.name).setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Match - connect"){_,_->
            if(pin.text.length!=4){message("Enter the PC's four-digit PIN.");return@setPositiveButton}
            working=true;message("Connecting securely...")
            LanSyncManager.pairWithPin(this,pc.ip,pin.text.toString(),pc.fingerprint,pc.id){ok,text->working=false;message(text);if(ok)finish()}
        }.show()
    }
    private fun acceptCode(raw:String){
        if(working)return
        if(!ProAccess.status(this).active){message("Restore or activate Pro in Settings to connect devices.");return}
        val invite=try{SecureLan.parseInvitation(raw)}catch(e:Exception){message(e.message ?: "Invalid code");return}
        fun connect(){
            working=true;message("Connecting securely to ${invite.name}…")
            fun attempt(index:Int){
                LanSyncManager.pairWithPin(this,invite.hosts[index],invite.secret,invite.fingerprint,invite.deviceId){ok,text->
                    if(ok){working=false;message(text);Toast.makeText(this,text,Toast.LENGTH_LONG).show();finish()}
                    else if(index+1<invite.hosts.size)attempt(index+1) else{working=false;message(text)}
                }
            };attempt(0)
        }
        if(LanSyncManager.isPaired(this)&&getSharedPreferences("sidenote_sync",0).getString("paired_device_id",null)!=invite.deviceId)
            AlertDialog.Builder(this).setTitle("Switch to ${invite.name}?").setMessage("Existing notes stay on this phone and will merge with the new PC. Your previous PC stays in Saved PCs.").setNegativeButton("Cancel",null).setPositiveButton("Switch"){_,_->connect()}.show()
        else connect()
    }
}
