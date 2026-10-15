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

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSyncing = AtomicBoolean(false)
    private val isListening = AtomicBoolean(false)

    data class DiscoveredPc(val ip: String, val deviceId: String, val name: String)

    fun isPaired(context: Context): Boolean {
        val prefs = context.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
        return !prefs.getString("auth_token", null).isNullOrBlank()
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
            .remove("auth_token")
            .remove("paired_device_id")
            .remove("paired_device_name")
            .remove("last_pc_ip")
            .apply()
    }

    fun startBackgroundListener(context: Context) {
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
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val json = JSONObject(text)
                        val cmd = json.optString("cmd", "")

                        if (cmd == "SIDENOTE_NOTIFY") {
                            // Windows PC notified that a note was edited on PC! Trigger instant pull
                            triggerSync(appContext)
                        } else if (cmd == "SIDENOTE_ANNOUNCE") {
                            val ip = packet.address.hostAddress ?: ""
                            val pcName = json.optString("deviceName", "Windows PC")
                            val pcId = json.optString("deviceId", "")
                            if (ip.isNotBlank()) {
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

    fun discoverPc(context: Context, callback: (DiscoveredPc?) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var result: DiscoveredPc? = null
            var socket: DatagramSocket? = null
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("SideNoteDiscoveryLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }

                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 2000
                }

                val syncStore = SyncStore(appContext)
                val probe = JSONObject().apply {
                    put("cmd", "SIDENOTE_DISCOVER")
                    put("deviceId", syncStore.deviceId)
                    put("deviceName", Build.MODEL ?: "Android Phone")
                    put("platform", "android")
                }.toString().toByteArray()

                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(probe, probe.size, broadcastAddr, UDP_PORT)
                socket.send(sendPacket)

                val buf = ByteArray(4096)
                val recvPacket = DatagramPacket(buf, buf.size)
                socket.receive(recvPacket)

                val respText = String(recvPacket.data, 0, recvPacket.length)
                val json = JSONObject(respText)
                if (json.optString("cmd") == "SIDENOTE_ANNOUNCE") {
                    val ip = recvPacket.address.hostAddress ?: ""
                    val name = json.optString("deviceName", "Windows PC")
                    val id = json.optString("deviceId", "")
                    if (ip.isNotBlank()) {
                        result = DiscoveredPc(ip, id, name)
                        appContext.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
                            .edit()
                            .putString("last_pc_ip", ip)
                            .apply()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Exception) {}
                mainHandler.post { callback(result) }
            }
        }
    }

    fun pairWithPin(context: Context, pcIp: String, pin: String, callback: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            var success = false
            var message = ""
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(pcIp.trim(), TCP_PORT), 5000)
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

                    appContext.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
                        .edit()
                        .putString("auth_token", token)
                        .putString("paired_device_id", pcDeviceId)
                        .putString("paired_device_name", pcName)
                        .putString("last_pc_ip", pcIp.trim())
                        .apply()

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
            callback?.let { cb -> mainHandler.post { cb(false, "Sync already in progress") } }
            return
        }

        executor.execute {
            var success = false
            var msg = ""
            var socket: Socket? = null
            try {
                val prefs = appContext.getSharedPreferences("sidenote_sync", Context.MODE_PRIVATE)
                val token = prefs.getString("auth_token", null)
                val pairedId = prefs.getString("paired_device_id", null)

                if (token.isNullOrBlank() || pairedId.isNullOrBlank()) {
                    msg = "Device not paired"
                    return@execute
                }

                var ip = prefs.getString("last_pc_ip", null)
                if (ip.isNullOrBlank()) {
                    // Try auto-discovery
                    val latch = java.util.concurrent.CountDownLatch(1)
                    discoverPc(appContext) { discovered ->
                        if (discovered != null) ip = discovered.ip
                        latch.countDown()
                    }
                    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }

                if (ip.isNullOrBlank()) {
                    msg = "Windows PC not found on local Wi-Fi"
                    return@execute
                }

                socket = Socket()
                socket.connect(InetSocketAddress(ip, TCP_PORT), 4000)
                socket.soTimeout = 10000

                val inStream = DataInputStream(socket.getInputStream())
                val outStream = DataOutputStream(socket.getOutputStream())

                val syncStore = SyncStore(appContext)
                val ackCursor = syncStore.peerCursor(pairedId)
                val outgoingBundle = syncStore.exportBundle(ackCursor)

                val req = JSONObject().apply {
                    put("type", "SYNC_REQUEST")
                    put("deviceId", syncStore.deviceId)
                    put("token", token)
                    put("ackCursor", ackCursor)
                    put("bundle", outgoingBundle)
                }.toString()

                writeFramed(outStream, req)
                val respStr = readFramed(inStream)
                val resp = JSONObject(respStr)

                if (resp.optString("status") == "ok") {
                    val incomingBundle = resp.optString("bundle", "")
                    if (incomingBundle.isNotBlank() && incomingBundle != "{}") {
                        val report = syncStore.importBundle(incomingBundle)
                        val incomingObj = JSONObject(incomingBundle)
                        val changes = incomingObj.optJSONArray("changes")
                        if (changes != null && changes.length() > 0) {
                            val lastSeq = changes.getJSONObject(changes.length() - 1).optLong("seq", 0L)
                            if (lastSeq > 0) {
                                syncStore.advancePeerCursor(pairedId, lastSeq)
                            }
                        }
                    }

                    prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
                    success = true
                    msg = "Sync complete"

                    // Notify UI activities to reload
                    mainHandler.post {
                        appContext.sendBroadcast(Intent(ACTION_SYNC_UPDATED))
                    }
                } else {
                    msg = resp.optString("error", "Sync rejected")
                }
            } catch (e: Exception) {
                msg = "Sync failed: ${e.message}"
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                isSyncing.set(false)
                callback?.let { cb -> mainHandler.post { cb(success, msg) } }
            }
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
