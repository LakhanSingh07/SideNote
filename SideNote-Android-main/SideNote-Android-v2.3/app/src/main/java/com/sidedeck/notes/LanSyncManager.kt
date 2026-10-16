package com.sidedeck.notes

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LanSyncManager {
    const val TCP_PORT = 43861
    const val UDP_PORT = 43862
    const val ACTION_SYNC_UPDATED = "com.sidedeck.notes.SYNC_UPDATED"

    private class SyncQueuedException:Exception()
    private class ConnectionChangedException:Exception()
    private val generation=java.util.concurrent.atomic.AtomicLong(0)
    fun connectionChanged(c:Context){generation.incrementAndGet();SecureLan.closeConnections();syncRequested.set(isPaired(c));c.getSharedPreferences("sync_health",0).edit().clear().apply();SyncHealth.update(c,if(isPaired(c))"paired" else "unpaired")}
    fun revokePeer(context:Context,peer:JSONObject){executor.execute {
        val result=runCatching {val token=SecureLan.unprotect(peer.getString("token"),peer.getString("id"));exchange(peer.getString("ip"),peer.getString("fingerprint"),JSONObject().put("type","UNPAIR").put("deviceId",SyncStore(context).deviceId).put("token",token))}
        mainHandler.post {android.widget.Toast.makeText(context,if(result.isSuccess)"PC access revoked" else "Forgot here. Remove this phone from the offline PC to revoke access there.",android.widget.Toast.LENGTH_LONG).show()}
    }}

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSyncing = AtomicBoolean(false)
    private val syncRequested = AtomicBoolean(false)
    private val retryStarted = AtomicBoolean(false)
    private val isListening = AtomicBoolean(false)

    data class DiscoveredPc(val ip: String, val deviceId: String, val name: String)

    fun isPaired(context: Context): Boolean {
        val prefs = context.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
        return !prefs.getString("auth_token_v2", null).isNullOrBlank() || !prefs.getString("auth_token", null).isNullOrBlank()
    }

    fun getPairedPcName(context: Context): String? {
        val prefs = context.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
        return prefs.getString("paired_device_name", null)
    }

    fun getLastSyncTime(context: Context): Long {
        val prefs = context.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
        return prefs.getLong("last_sync_time", 0L)
    }

    fun unpair(context: Context) {
        val prefs = context.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("auth_token").remove("auth_token_v2").remove("tls_fingerprint").remove("last_sync_time")
            .remove("paired_device_id")
            .remove("paired_device_name")
            .remove("last_pc_ip")
            .apply()
        connectionChanged(context)
    }

    fun startBackgroundListener(context: Context) {
        if(retryStarted.compareAndSet(false,true)) {
            executor.execute {while(true){try{Thread.sleep(15000);if(isPaired(context.applicationContext))triggerSync(context.applicationContext)}catch(_:InterruptedException){break}}}
        }
        if (isListening.getAndSet(true)) return
        val appContext = context.applicationContext
        executor.execute {
            var multicastLock: WifiManager.MulticastLock? = null
            var socket: DatagramSocket? = null
            try {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("SideNoteSyncLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }

                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_PORT))
                }

                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isListening.get()) {
                    try {
                        packet.length=buffer.size
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val json = JSONObject(text)
                        val cmd = json.optString("cmd", "")

                        if (cmd == "SIDENOTE_NOTIFY") {
                            // Windows PC notified that a note was edited on PC! Trigger instant pull
                            val prefs=appContext.getSharedPreferences("sidenote_sync",Context.MODE_PRIVATE)
                            if(json.optString("deviceId")==prefs.getString("paired_device_id",null) && packet.address.hostAddress==prefs.getString("last_pc_ip",null))triggerSync(appContext)
                        } else if (cmd == "SIDENOTE_ANNOUNCE") {
                            val ip = packet.address.hostAddress ?: ""
                            val pcName = json.optString("deviceName", "Windows PC")
                            val pcId = json.optString("deviceId", "")
                            val paired=context.getSharedPreferences("sidenote_sync",Context.MODE_PRIVATE).getString("paired_device_id",null)
                            if (ip.isNotBlank() && pcId==paired) {
                                appContext.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("last_pc_ip", ip)
                                    .apply()
                            }
                        }
                    } catch (_: Exception) {
                        Thread.sleep(500)
                    }
                }
            } catch (_: Exception) {
            } finally {
                isListening.set(false)
                try { socket?.close() } catch (_: Exception) {}
                try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Exception) {}
            }
        }
    }

    fun discoverPc(context:Context,callback:(DiscoveredPc?)->Unit){
        val prefs=context.getSharedPreferences("sidenote_sync",0)
        val paired=prefs.getString("paired_device_id",null)
        PcDiscovery.scan(context){pcs->
            val result=if(paired==null)pcs.firstOrNull() else pcs.firstOrNull {it.id==paired}
            if(result!=null&&paired==result.id)prefs.edit().putString("last_pc_ip",result.ip).apply()
            callback(result?.let {DiscoveredPc(it.ip,it.id,it.name)})
        }
    }

    fun pairWithPin(context: Context, pcIp: String, pin: String, fingerprint:String, expectedId:String?=null, callback: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var success = false
            var message = ""
            var socket: Socket? = null
            try {
                socket = SecureLan.connect(pcIp.trim(),fingerprint)
                socket.soTimeout = 8000

                val inStream = DataInputStream(socket.getInputStream())
                val outStream = DataOutputStream(socket.getOutputStream())

                val syncStore = SyncStore(appContext)
                val req = JSONObject().apply {
                    put("type", "PAIR_VERIFY")
                    put("deviceId", syncStore.deviceId)
                    put("deviceName", Build.MODEL ?: "Android Phone")
                    put("pin", pin.trim())
                }.toString()

                writeFramed(outStream, req)
                val respStr = readFramed(inStream)
                val resp = JSONObject(respStr)

                if (resp.optString("status") == "ok") {
                    val token = resp.getString("token")
                    val pcDeviceId = resp.optString("deviceId", "")
                    val pcName = resp.optString("deviceName", "Windows PC")

                    require(expectedId==null || expectedId==pcDeviceId){"PC identity does not match this invitation"}
                    SecureLan.savePeer(appContext,pcDeviceId,pcName,pcIp.trim(),fingerprint,token)

                    success = true
                    message = "Paired with $pcName!"
                } else {
                    message = resp.optString("error", "Invalid 4-digit PIN")
                }
            } catch (e: Exception) {
                message = "Connection failed: ${e.message}"
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                mainHandler.post {
                    callback(success, message)
                    if (success) {
                        triggerSync(appContext)
                    }
                }
            }
        }
    }

    fun triggerSync(context: Context, callback: ((Boolean, String) -> Unit)? = null) {
        val appContext = context.applicationContext
        if (isSyncing.getAndSet(true)) {
            if(callback==null)syncRequested.set(true)
            callback?.let { cb -> mainHandler.post { cb(false, "Sync queued") } }
            return
        }

        executor.execute {
            val activeGeneration=generation.get()
            fun ensureSession(){if(activeGeneration!=generation.get())throw ConnectionChangedException()}

            var success = false
            var msg = ""
            var socket: Socket? = null
            try {
                val prefs = appContext.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
                val token = if(isPaired(appContext)) SecureLan.token(appContext) else null
                val pairedId = prefs.getString("paired_device_id", null)

                if (token.isNullOrBlank() || pairedId.isNullOrBlank()) {
                    msg = "Device not paired"
                    return@execute
                }

                require(ProAccess.status(appContext).active){"Pro access is required for cross-device sync. Restore or activate Pro in Settings."}
                var ip = prefs.getString("last_pc_ip", null)
                if (ip.isNullOrBlank()) {
                    // Try auto-discovery
                    val latch = java.util.concurrent.CountDownLatch(1)
                    discoverPc(appContext) { discovered ->
                        if (discovered != null) ip = discovered.ip
                        latch.countDown()
                    }
                    latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
                }

                if (ip.isNullOrBlank()) {
                    msg = "Windows PC not found on local Wi-Fi"
                    return@execute
                }

                val syncStore = SyncStore(appContext)
                syncStore.ensureLocalSnapshot()
                syncStore.ensurePeerSnapshot(pairedId)
                val fingerprint=prefs.getString("tls_fingerprint","")!!
                SyncHealth.update(appContext,"syncing")
                var conflicts=0;var appliedChanges=0
                while(true) {
                    ensureSession()
                    val downloadCursor=syncStore.peerCursor(pairedId)
                    val uploadCursor=syncStore.uploadCursor(pairedId)
                    val outgoing=syncStore.exportBundle(uploadCursor)
                    val outgoingChanges=JSONObject(outgoing).getJSONArray("changes")
                    val response=exchange(ip!!,fingerprint,JSONObject().put("type","SYNC_REQUEST").put("deviceId",syncStore.deviceId)
                        .put("token",token).put("ackCursor",downloadCursor).put("bundle",outgoing))
                    require(response.optString("status")=="ok") {response.optString("error","Sync rejected")}
                    val incoming=response.getString("bundle")
                    val incomingObject=JSONObject(incoming)
                    require(incomingObject.getString("originDevice")==pairedId) {"Unexpected sync device"}
                    val incomingChanges=incomingObject.getJSONArray("changes")
                    ensureSession()
                    val report=syncStore.importBundle(incoming);conflicts+=report.conflicts;appliedChanges+=report.applied
                    if(incomingChanges.length()>0)syncStore.advancePeerCursor(pairedId,incomingChanges.getJSONObject(incomingChanges.length()-1).getLong("seq"))
                    val received=response.getLong("receivedCursor")
                    if(outgoingChanges.length()>0) {
                        val last=outgoingChanges.getJSONObject(outgoingChanges.length()-1).getLong("seq")
                        require(received>=last) {"PC did not acknowledge uploaded notes"}
                    }
                    syncStore.advanceUploadCursor(pairedId,received)
                    val remaining=JSONObject(syncStore.exportBundle(syncStore.uploadCursor(pairedId))).getJSONArray("changes").length()>0
                    if(!remaining && incomingChanges.length()==0)break
                }
                SyncHealth.contact(appContext)
                transferAttachments(appContext,ip!!,fingerprint,token,syncStore.deviceId,activeGeneration)
                ensureSession()
                prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
                SyncHealth.update(appContext,"synced")
                success=true;msg=if(conflicts>0) "Synced; $conflicts conflicts need review" else "Notes and files synced"
                if(appliedChanges>0 || conflicts>0)mainHandler.post {appContext.sendBroadcast(Intent(ACTION_SYNC_UPDATED).setPackage(appContext.packageName))}
            } catch (_:ConnectionChangedException) {msg="Selected device changed"}
            catch (_:SyncQueuedException) {
                msg="Sync queued"
            } catch (e: Exception) {
                msg = "Sync failed: ${e.message}"
                if(activeGeneration==generation.get()&&isPaired(appContext))SyncHealth.update(appContext,"offline",e.message.orEmpty())
                val discoveryPrefs=appContext.getSharedPreferences("sync_health",0)
                if(System.currentTimeMillis()-discoveryPrefs.getLong("last_discovery",0)>30000){
                    discoveryPrefs.edit().putLong("last_discovery",System.currentTimeMillis()).apply()
                    discoverPc(appContext){found->if(found!=null)triggerSync(appContext)}
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                isSyncing.set(false)
                if(syncRequested.getAndSet(false)) triggerSync(appContext)
                callback?.let { cb -> mainHandler.post { cb(success, msg) } }
            }
        }
    }

    private fun exchange(ip:String, fingerprint:String, request:JSONObject, bytes:ByteArray?=null):JSONObject {
        SecureLan.connect(ip,fingerprint).use { socket ->
            val output=DataOutputStream(socket.getOutputStream());val input=DataInputStream(socket.getInputStream())
            writeFramed(output,request.toString());if(bytes!=null){output.write(bytes);output.flush()}
            return JSONObject(readFramed(input))
        }
    }
    private fun transferAttachments(context:Context,ip:String,fingerprint:String,token:String,deviceId:String,activeGeneration:Long) {
        val prefs=context.getSharedPreferences("sidenote_transfers",Context.MODE_PRIVATE)
        for(note in NotesStore(context).all())for(file in note.attachments){
            if(activeGeneration!=generation.get())throw ConnectionChangedException()
            fun request(op:String,offset:Long=0,length:Int=0)=JSONObject().put("type","FILE_REQUEST").put("deviceId",deviceId)
                .put("token",token).put("op",op).put("noteId",note.id).put("attachmentId",file.id).put("offset",offset).put("length",length)
            fun progress(direction:String,bytes:Long){
                val pct=if(file.size==0L)100 else (bytes.toDouble()/file.size*100).toInt()
                prefs.edit().putString(file.sha256,"$direction $pct% ? ${android.text.format.Formatter.formatFileSize(context,bytes)} / ${android.text.format.Formatter.formatFileSize(context,file.size)}").apply()
            }
            try {
                val remote=exchange(ip,fingerprint,request("status"));require(remote.optString("status")=="ok"){remote.optString("error","File status unavailable")}
                val local=AttachmentStore.available(context,file)
                if(local && !remote.optBoolean("complete")) {
                    var offset=remote.getLong("bytes");require(offset in 0..file.size){"Invalid resume offset"}
                    java.io.RandomAccessFile(AttachmentStore.path(context,file.sha256),"r").use { source ->
                        source.seek(offset)
                        do {
                            if(activeGeneration!=generation.get())throw ConnectionChangedException()
                            if(syncRequested.get())throw SyncQueuedException()
                            val count=minOf(AttachmentStore.CHUNK_SIZE.toLong(),file.size-offset).toInt()
                            val buffer=ByteArray(count);source.readFully(buffer)
                            val response=exchange(ip,fingerprint,request("put",offset,count),buffer)
                            require(response.optString("status")=="ok"){response.optString("error","Upload failed")}
                            val next=response.getLong("bytes");require(next==offset+count){"Invalid uploaded offset"};offset=next;progress("Sending",offset)
                            if(response.optBoolean("complete"))break
                            require(count>0){"Upload did not complete"}
                        }while(offset<file.size)
                    }
                } else if(!local && remote.optBoolean("complete")) {
                    val partial=AttachmentStore.path(context,file.sha256,true)
                    if(partial.length()>file.size)check(partial.delete())
                    var offset=partial.length()
                    java.io.RandomAccessFile(partial,"rw").use { target ->
                        target.seek(offset)
                        while(offset<file.size){
                            if(activeGeneration!=generation.get())throw ConnectionChangedException()
                            if(syncRequested.get())throw SyncQueuedException()
                            val count=minOf(AttachmentStore.CHUNK_SIZE.toLong(),file.size-offset).toInt()
                            SecureLan.connect(ip,fingerprint).use { connection ->
                                val input=DataInputStream(connection.getInputStream());writeFramed(DataOutputStream(connection.getOutputStream()),request("get",offset,count).toString())
                                val reply=JSONObject(readFramed(input));require(reply.optString("status")=="ok"){reply.optString("error","Download failed")}
                                require(reply.getLong("offset")==offset && reply.getInt("length")==count && reply.getLong("total")==file.size){"Invalid file chunk"}
                                val buffer=ByteArray(count);input.readFully(buffer);if(activeGeneration!=generation.get())throw ConnectionChangedException();target.write(buffer);offset+=count;progress("Receiving",offset)
                            }
                        }
                        target.fd.sync()
                    }
                    try{AttachmentStore.finishDownload(context,file)}catch(e:Exception){partial.delete();throw e}
                } else if(!local) {prefs.edit().putString(file.sha256,"Waiting for the sender to finish uploading").apply();continue}
                exchange(ip,fingerprint,request("received"))
                prefs.edit().putString(file.sha256,"Available on both devices").apply()
            }catch(e:ConnectionChangedException){throw e}catch(e:SyncQueuedException){throw e}catch(e:Exception){prefs.edit().putString(file.sha256,"Transfer paused: ${e.message ?: "Connection interrupted"}. Retry will resume.").apply();throw e}
        }
    }

    private fun writeFramed(out: DataOutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readFramed(input: DataInputStream): String {
        val len = input.readInt()
        if (len < 0 || len > 6_000_000) throw IllegalArgumentException("Invalid frame length: $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }
}
