package com.sidedeck.notes
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.spec.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Executors

object ProAccess {
    data class Access(val active:Boolean,val preview:Boolean,val message:String,val plan:String="free",val until:Long=0)
    private val worker=Executors.newSingleThreadExecutor()
    fun configured()=BuildConfig.API_BASE.startsWith("https://")&&BuildConfig.LICENSE_PUBLIC_KEY.isNotBlank()
    fun preview()=!BuildConfig.COMMERCIAL_BUILD
    fun verifyTicket(raw:String,publicKey:String,deviceId:String,now:Long=System.currentTimeMillis()/1000,lastClock:Long=0):JSONObject {
        require(raw.length<=16000&&now+300>=lastClock){"Check the device date and restore Pro access"}
        val parts=raw.split('.');require(parts.size==3)
        fun decode(s:String)=Base64.decode(s,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val header=JSONObject(String(decode(parts[0])));require(header.optString("alg")=="ES256"&&header.optString("typ")=="JWT")
        val public=Base64.decode(publicKey,Base64.NO_WRAP);require(public.size==65&&public[0].toInt()==4)
        val parameters=AlgorithmParameters.getInstance("EC").apply {init(ECGenParameterSpec("secp256r1"))}.getParameterSpec(ECParameterSpec::class.java)
        val key=KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1,public.copyOfRange(1,33)),BigInteger(1,public.copyOfRange(33,65))),parameters))
        val signature=decode(parts[2]);require(signature.size==64)
        fun integer(bytes:ByteArray):ByteArray {val raw=bytes.dropWhile {it.toInt()==0}.toByteArray();val trimmed=if(raw.isEmpty())byteArrayOf(0)else raw;val positive=if((trimmed[0].toInt() and 128)!=0)byteArrayOf(0)+trimmed else trimmed;return byteArrayOf(2,positive.size.toByte())+positive}
        val integers=integer(signature.copyOfRange(0,32))+integer(signature.copyOfRange(32,64));val der=byteArrayOf(48,integers.size.toByte())+integers
        require(Signature.getInstance("SHA256withECDSA").apply {initVerify(key);update((parts[0]+"."+parts[1]).toByteArray())}.verify(der)){"Invalid entitlement signature"}
        val c=JSONObject(String(decode(parts[1])));val expires=c.getLong("exp");val issued=c.getLong("iat")
        require(c.getString("iss")=="sidenote-pro"&&c.getString("aud")=="sidenote-apps"&&c.getString("deviceId")==deviceId&&expires>now&&issued<=now+300&&expires-issued<=72*3600){"Pro access expired or belongs to another device. Restore your purchase."}
        require(!c.getBoolean("active")||c.getLong("accessUntil")>=expires);return c
    }
    fun status(c:Context):Access {
        if(BuildConfig.ANDROID_FREE_BETA)return Access(true,true,"Android early access is free. Sync availability depends on your connected Windows PC trial or Pro access.","android-beta")
        val p=c.getSharedPreferences("pro_access",0);val preview=preview()
        if(!configured())return Access(preview,preview,if(preview)"Preview build - Pro features enabled for testing. No subscription is charged." else "Free basic. Local notes and export remain available.")
        val raw=p.getString("ticket",null)
        val result=runCatching {val claims=verifyTicket(raw ?: error("Restore or activate your Pro subscription"),BuildConfig.LICENSE_PUBLIC_KEY,SyncStore(c).deviceId,lastClock=p.getLong("last_clock",0));Access(claims.getBoolean("active")||preview,preview,if(claims.getBoolean("active"))"Pro access verified" else "Subscription inactive. Local notes and export remain available.",claims.getString("plan"),claims.getLong("accessUntil"))}.getOrElse {Access(preview,preview,it.message ?: "Restore Pro access")}
        p.edit().putLong("last_clock",maxOf(p.getLong("last_clock",0),System.currentTimeMillis()/1000)).apply();return result
    }
    private fun request(path:String,body:JSONObject):JSONObject {
        require(configured()){"Purchases are not configured in this preview build"}
        val url=URL(BuildConfig.API_BASE.trimEnd('/')+"/v1/"+path);require(url.protocol=="https")
        val connection=url.openConnection() as HttpsURLConnection
        try{connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=20000;connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use {it.write(body.toString().toByteArray())}
            val ok=connection.responseCode in 200..299;val input=if(ok)connection.inputStream else connection.errorStream ?: error("Verification unavailable")
            val bytes=input.use {stream->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096);while(true){val count=stream.read(buffer);if(count<0)break;require(out.size()+count<=65536);out.write(buffer,0,count)};out.toByteArray()}
            val result=JSONObject(String(bytes,Charsets.UTF_8));require(ok){result.optString("error","Verification unavailable")};return result
        }finally{connection.disconnect()}
    }
    private fun save(c:Context,result:JSONObject,code:String?){
        val id=SyncStore(c).deviceId;val raw=result.getString("ticket");verifyTicket(raw,BuildConfig.LICENSE_PUBLIC_KEY,id)
        val edit=c.getSharedPreferences("pro_access",0).edit().putString("ticket",raw).putLong("last_clock",System.currentTimeMillis()/1000)
        val activation=code ?: result.optString("activationCode").takeIf {it.isNotBlank()};if(activation!=null)edit.putString("activation_enc",SecureLan.protect(activation,"license:$id"));check(edit.commit())
    }
    fun activationCode(c:Context):String {val value=c.getSharedPreferences("pro_access",0).getString("activation_enc",null) ?: error("Restore or activate Pro first");return SecureLan.unprotect(value,"license:${SyncStore(c).deviceId}")}
    fun activate(c:Context,code:String?,done:(Boolean,String)->Unit){worker.execute {
        val result=runCatching {val key=code?.trim() ?: activationCode(c);require(key.matches(Regex("[a-f0-9]{64}"))){"Enter a valid activation code"};val result=request("activate",JSONObject().put("activationCode",key).put("deviceId",SyncStore(c).deviceId));save(c,result,key);status(c).message}
        android.os.Handler(android.os.Looper.getMainLooper()).post {done(result.isSuccess,result.getOrElse {it.message ?: "Verification failed"});if(result.isSuccess)LanSyncManager.triggerSync(c)}
    }}
    fun verifyPlay(c:Context,purchaseToken:String,done:(Boolean,String)->Unit){worker.execute {
        val result=runCatching {val response=request("play/verify",JSONObject().put("purchaseToken",purchaseToken).put("deviceId",SyncStore(c).deviceId));save(c,response,null);status(c).message}
        android.os.Handler(android.os.Looper.getMainLooper()).post {done(result.isSuccess,result.getOrElse {it.message ?: "Purchase verification unavailable. Use Restore Purchase to retry."})}
    }}
}
