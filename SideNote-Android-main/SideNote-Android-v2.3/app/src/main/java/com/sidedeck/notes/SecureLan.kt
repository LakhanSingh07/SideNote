package com.sidedeck.notes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.*

/** Pinned TLS, including self-signed LAN identities. Never accepts an unpinned certificate. */
object SecureLan {
    private val connections=java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    fun closeConnections(){connections.forEach {runCatching {it.close()}};connections.clear()}

    data class Invitation(val hosts:List<String>,val deviceId:String,val name:String,val fingerprint:String,val secret:String)
    fun parseInvitation(raw:String,now:Long=System.currentTimeMillis()/1000):Invitation {
        require(raw.length<=8192){"Pairing code is too large"}
        val o=JSONObject(raw);require(o.optString("app")=="SideNote" && o.optInt("version")==2){"Not a SideNote pairing code"}
        require(o.getLong("expiresAt") in now..now+180){"This code expired. Generate a new one on your PC."}
        val a=o.getJSONArray("hosts");require(a.length() in 1..20){"No local PC address"}
        val hosts=(0 until a.length()).map {a.getString(it)};require(hosts.all(::localAddress)){"Only local network addresses are accepted"}
        val fingerprint=o.getString("fingerprint").lowercase();val secret=o.getString("secret")
        require(fingerprint.matches(Regex("[a-f0-9]{64}")) && secret.matches(Regex("[a-f0-9]{64}"))){"Invalid secure pairing code"}
        val id=o.getString("deviceId");require(id.length in 1..128)
        return Invitation(hosts,id,o.optString("name","Windows PC").take(100),fingerprint,secret)
    }
    fun localAddress(host:String):Boolean {
        val p=host.split('.').map {it.toIntOrNull() ?: return false};if(p.size!=4 || p.any {it !in 0..255})return false
        return p[0]==10 || p[0]==192&&p[1]==168 || p[0]==172&&p[1] in 16..31 || p[0]==169&&p[1]==254 || p[0]==127
    }
    fun connect(ip:String,fingerprint:String):SSLSocket {
        require(localAddress(ip)){"Enter a local Wi-Fi IPv4 address"}
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))){"Secure pairing required. Scan the PC's new QR code in Settings."}
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String){throw java.security.cert.CertificateException("Client verification is not supported")}
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String){
                if(chain.isEmpty())throw java.security.cert.CertificateException("Missing PC certificate")
                val expected=fingerprint.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
                if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)))throw java.security.cert.CertificateException("PC identity changed. Verify and pair again.")
                chain[0].checkValidity()
            }
        }
        val context=SSLContext.getInstance("TLS");context.init(null,arrayOf(trust),SecureRandom())
        connections.removeIf {it.isClosed}
        val plain=Socket();connections.add(plain)
        try {
            plain.connect(InetSocketAddress(ip,LanSyncManager.TCP_PORT),5000);plain.soTimeout=60000
            val socket=context.socketFactory.createSocket(plain,"sidenote.local",LanSyncManager.TCP_PORT,true) as SSLSocket
            socket.enabledProtocols=socket.supportedProtocols.filter {it=="TLSv1.3"||it=="TLSv1.2"}.toTypedArray()
            connections.add(socket);socket.startHandshake();return socket
        }catch(e:Exception){plain.close();throw e}
    }
    @Synchronized private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey("sidenote_pairing_v2",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("sidenote_pairing_v2",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun protect(token:String,deviceId:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(deviceId.toByteArray())
        return Base64.encodeToString(cipher.iv+cipher.doFinal(token.toByteArray()),Base64.NO_WRAP)
    }
    fun token(context:Context):String {
        val p=context.getSharedPreferences("sidenote_sync",0);val value=p.getString("auth_token_v2",null)
        if(value==null) return p.getString("auth_token",null) ?: error("Device not paired")
        return unprotect(value,p.getString("paired_device_id","")!!)
    }
    fun unprotect(value:String,deviceId:String):String {
        val bytes=Base64.decode(value,Base64.NO_WRAP);require(bytes.size>28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD(deviceId.toByteArray());return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }

    fun savePeer(context:Context,id:String,name:String,ip:String,fingerprint:String,token:String){
        val p=context.getSharedPreferences("sidenote_sync",0);val encrypted=protect(token,id)
        val profiles=JSONArray(p.getString("saved_pcs","[]"));val list=(0 until profiles.length()).map {profiles.getJSONObject(it)}.filterNot {it.optString("id")==id}
        val peer=JSONObject().put("id",id).put("name",name).put("ip",ip).put("fingerprint",fingerprint).put("token",encrypted)
        check(p.edit().putString("saved_pcs",JSONArray(list+peer).toString()).putString("auth_token_v2",encrypted).remove("auth_token")
            .putString("paired_device_id",id).putString("paired_device_name",name).putString("last_pc_ip",ip).putString("tls_fingerprint",fingerprint).commit())
        LanSyncManager.connectionChanged(context)
    }
    fun savedPeers(context:Context):List<JSONObject>{val a=JSONArray(context.getSharedPreferences("sidenote_sync",0).getString("saved_pcs","[]"));return (0 until a.length()).map {a.getJSONObject(it)}}
    fun selectPeer(context:Context,id:String){
        val peer=savedPeers(context).first {it.getString("id")==id};val p=context.getSharedPreferences("sidenote_sync",0)
        check(p.edit().putString("paired_device_id",id).putString("paired_device_name",peer.getString("name")).putString("last_pc_ip",peer.getString("ip"))
            .putString("tls_fingerprint",peer.getString("fingerprint")).putString("auth_token_v2",peer.getString("token")).remove("auth_token").remove("last_sync_time").commit())
        LanSyncManager.connectionChanged(context)
        LanSyncManager.triggerSync(context)
    }
    fun forgetPeer(context:Context,id:String){
        val removed=savedPeers(context).firstOrNull {it.getString("id")==id}
        val p=context.getSharedPreferences("sidenote_sync",0);val kept=savedPeers(context).filterNot {it.getString("id")==id}
        p.edit().putString("saved_pcs",JSONArray(kept).toString()).commit()
        if(p.getString("paired_device_id",null)==id)LanSyncManager.unpair(context)
        removed?.let {LanSyncManager.revokePeer(context,it)}
    }
}
