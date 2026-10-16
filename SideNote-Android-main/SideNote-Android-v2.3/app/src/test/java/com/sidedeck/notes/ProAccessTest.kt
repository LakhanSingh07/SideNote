package com.sidedeck.notes
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class ProAccessTest {
 @Test fun androidEarlyAccessNeverRequiresAPurchase(){val c=org.robolectric.RuntimeEnvironment.getApplication();val status=ProAccess.status(c);assertTrue(status.active);assertEquals("android-beta",status.plan)}
 private fun fixture()=JSONObject(javaClass.classLoader!!.getResourceAsStream("entitlement.json")!!.bufferedReader().use {it.readText()})
 @Test fun backendSignatureIsCompatibleWithAndroid(){val f=fixture();assertTrue(ProAccess.verifyTicket(f.getString("token"),f.getString("publicKey"),f.getString("deviceId"),f.getLong("now")).getBoolean("active"))}
 @Test fun expiredOrWrongDeviceTicketIsRejected(){val f=fixture();assertThrows(IllegalArgumentException::class.java){ProAccess.verifyTicket(f.getString("token"),f.getString("publicKey"),"other",f.getLong("now"))};assertThrows(IllegalArgumentException::class.java){ProAccess.verifyTicket(f.getString("token"),f.getString("publicKey"),f.getString("deviceId"),2000003601)}}
 @Test fun clockRollbackDoesNotExtendOfflineAccess(){val f=fixture();assertThrows(IllegalArgumentException::class.java){ProAccess.verifyTicket(f.getString("token"),f.getString("publicKey"),f.getString("deviceId"),f.getLong("now"),2000000500)}}
}
