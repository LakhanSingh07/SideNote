package com.sidedeck.notes

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

object PcDiscovery {
    data class Pc(val ip:String,val id:String,val name:String,val fingerprint:String)
    private val worker=Executors.newCachedThreadPool()
    fun scan(c:Context,callback:(List<Pc>)->Unit){
        val found=ConcurrentHashMap<String,Pc>();val main=Handler(Looper.getMainLooper())
        val manager=c.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val listener=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String){}
            override fun onDiscoveryStopped(type:String){}
            override fun onServiceLost(info:NsdServiceInfo){}
            override fun onStartDiscoveryFailed(type:String,code:Int){}
            override fun onStopDiscoveryFailed(type:String,code:Int){}
            override fun onServiceFound(info:NsdServiceInfo){
                @Suppress("DEPRECATION")
                manager?.resolveService(info,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(info:NsdServiceInfo,error:Int){}
                    override fun onServiceResolved(service:NsdServiceInfo){
                        @Suppress("DEPRECATION") val ip=service.host?.hostAddress ?: return
                        if(!SecureLan.localAddress(ip))return
                        fun attr(key:String)=service.attributes[key]?.toString(Charsets.UTF_8).orEmpty()
                        val id=attr("deviceId");if(id.isNotBlank())found[id]=Pc(ip,id,attr("name").ifBlank {service.serviceName},attr("fingerprint"))
                    }
                })
            }
        }
        runCatching{manager?.discoverServices("_sidenote._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)}
        worker.execute {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast=true;socket.soTimeout=350
                    val targets=mutableSetOf("255.255.255.255")
                    NetworkInterface.getNetworkInterfaces()?.toList()?.filter {it.isUp&&!it.isLoopback}?.forEach {network->network.interfaceAddresses.mapNotNull {it.broadcast?.hostAddress}.forEach(targets::add)}
                    c.getSharedPreferences("sidenote_sync",0).getString("last_pc_ip",null)?.let(targets::add)
                    val bytes=JSONObject().put("cmd","SIDENOTE_DISCOVER").put("deviceId",SyncStore(c).deviceId).put("platform","android").toString().toByteArray()
                    for(target in targets)runCatching{socket.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName(target),LanSyncManager.UDP_PORT))}
                    val end=System.currentTimeMillis()+2800
                    while(System.currentTimeMillis()<end)runCatching {
                        val packet=DatagramPacket(ByteArray(4096),4096);socket.receive(packet)
                        val json=JSONObject(String(packet.data,0,packet.length));val ip=packet.address.hostAddress.orEmpty()
                        if(json.optString("cmd")=="SIDENOTE_ANNOUNCE"&&SecureLan.localAddress(ip)){
                            val id=json.optString("deviceId");if(id.isNotBlank())found[id]=Pc(ip,id,json.optString("deviceName","Windows PC"),json.optString("fingerprint"))
                        }
                    }
                }
            }
        }
        main.postDelayed({runCatching{manager?.stopServiceDiscovery(listener)};callback(found.values.sortedBy {it.name})},3500)
    }
}
