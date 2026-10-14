package org.unityconnect.companion

import android.Manifest
import android.app.Notification
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowTelephony
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushNotificationsTest {
 private data class Sms(val id:Long,val address:String,val body:String,val date:Long,val type:Int=1)
 private class Provider : ContentProvider() {
  val rows=java.util.concurrent.CopyOnWriteArrayList<Sms>()
  val baselines=AtomicInteger()
  override fun onCreate()=true
  override fun getType(uri:Uri)="vnd.android.cursor.dir/sms"
  override fun insert(uri:Uri,values:ContentValues?)=null
  override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=0
  override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
  override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
   var values=rows.filter { uri.path != "/inbox" || it.type==1 }
   if (selection=="_id > ?") values=values.filter { it.id > args!![0].toLong() }
   if (selection=="date = ? AND body = ?") values=values.filter { it.date==args!![0].toLong() && it.body==args[1] }
   if (sort=="_id DESC") { baselines.incrementAndGet(); values=values.sortedByDescending { it.id } } else values=values.sortedBy { it.id }
   val columns=projection?:arrayOf("_id","address","body","date","type")
   return MatrixCursor(columns).apply { values.forEach { row -> addRow(Array<Any?>(columns.size) { index -> when(columns[index]) { "_id"->row.id;"address"->row.address;"body"->row.body;"date"->row.date;"type"->row.type;else->null } }) } }
  }
 }
 private fun grant()=shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_SMS,Manifest.permission.SEND_SMS)
 private fun incoming(message:JSONObject)=message.optString("type")=="event" && message.optString("feature")=="sms" && message.optJSONObject("data")?.has("incoming")==true
 @Test fun onlyNewInboxMessagesPushAndReconnectDoesNotReplayHistory() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val provider=Provider();provider.rows.add(Sms(1,"Old sender","Old message",1))
  provider.attachInfo(service,ProviderInfo().apply { authority="sms" });ShadowContentResolver.registerProviderInternal("sms",provider)
  grant();service.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("enable.sms",true).commit()
  val connection=TestConnection(service)
  try {
   service.features.sms.watch();shadowOf(Looper.getMainLooper()).idle();connection.await { provider.baselines.get()>0 }
   Thread.sleep(40);assertFalse(connection.lan.messages.any(::incoming))
   provider.rows.add(Sms(2,"+15550123","Hello தமிழ்",1000))
   service.contentResolver.notifyChange(Uri.parse("content://sms/inbox/2"),null);shadowOf(Looper.getMainLooper()).idle()
   val value=connection.lan.next(::incoming).getJSONObject("data").getJSONObject("incoming")
   assertEquals("2",value.getString("id"));assertEquals("Hello தமிழ்",value.getString("body"));assertEquals(1000L,value.getLong("timestamp"))
   // Read changes, outgoing rows and repeated provider signals are not incoming messages.
   provider.rows.add(Sms(3,"+15550123","Outgoing",1001,2))
   repeat(2) { service.contentResolver.notifyChange(Uri.parse("content://sms"),null);shadowOf(Looper.getMainLooper()).idle() }
   Thread.sleep(70);assertFalse(connection.lan.messages.any(::incoming))
   service.features.sms.stop();shadowOf(Looper.getMainLooper()).idle()
   provider.rows.add(Sms(4,"Sender","Offline",2000))
   val count=provider.baselines.get();service.features.sms.watch();shadowOf(Looper.getMainLooper()).idle();connection.await { provider.baselines.get()>count }
   service.contentResolver.notifyChange(Uri.parse("content://sms"),null);shadowOf(Looper.getMainLooper()).idle()
   Thread.sleep(70);assertFalse(connection.lan.messages.any(::incoming))
   provider.rows.add(Sms(5,"Sender","Back online",3000));service.contentResolver.notifyChange(Uri.parse("content://sms"),null);shadowOf(Looper.getMainLooper()).idle()
   assertEquals("5",connection.lan.next(::incoming).getJSONObject("data").getJSONObject("incoming").getString("id"))
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.sms",false).commit()
   provider.rows.add(Sms(6,"Sender","Disabled",4000));service.contentResolver.notifyChange(Uri.parse("content://sms"),null);shadowOf(Looper.getMainLooper()).idle()
   Thread.sleep(70);assertFalse(connection.lan.messages.any(::incoming))
  } finally { connection.close();controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun phonePostsAlertButListSnapshotsAndOngoingUpdatesStayQuiet() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.notifications",true).commit()
  val connection=TestConnection(service)
  val listenerController=Robolectric.buildService(PhoneNotifications::class.java).create();val listener=listenerController.get()
  listener.onListenerConnected()
  fun sbn(id:Int,ongoing:Boolean=false):StatusBarNotification {
   val n=Notification.Builder(service,"test").setContentTitle("Alex").setContentText("Message").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(ongoing).build()
   val key=shadowOf(listener).addActiveNotification("test.chat",id,n)
   return listener.activeNotifications.single { it.key==key }
  }
  fun mirrored(m:JSONObject)=m.optString("type")=="event"&&m.optString("feature")=="notifications"
  try {
   val posted=sbn(1);listener.onNotificationPosted(posted)
   assertTrue(connection.lan.next(::mirrored).getJSONObject("data").getBoolean("alert"))
   listener.publishAll()
   assertFalse(connection.lan.next(::mirrored).getJSONObject("data").getBoolean("alert"))
   listener.onNotificationPosted(sbn(2,true))
   assertFalse(connection.lan.next(::mirrored).getJSONObject("data").getBoolean("alert"))
   service.getSharedPreferences("settings",0).edit().putString("notification_excluded","test.chat").commit()
   listener.onNotificationPosted(sbn(3));Thread.sleep(70);assertFalse(connection.lan.messages.any(::mirrored))
  } finally { listenerController.destroy();connection.close();controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun smsAppCorrelationRequiresAnExactUniqueInboxRow() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val provider=Provider();provider.attachInfo(service,ProviderInfo().apply { authority="sms" });ShadowContentResolver.registerProviderInternal("sms",provider)
  grant();service.getSharedPreferences("settings",0).edit().putBoolean("enable.sms",true).commit()
  ShadowTelephony.ShadowSms.setDefaultSmsPackage("test.sms")
  try {
   provider.rows.add(Sms(10,"Sender","Match",1000))
   assertEquals("10",service.features.sms.notificationMessageId("test.sms",1000,"Match"))
   assertNull(service.features.sms.notificationMessageId("test.chat",1000,"Match"))
   assertNull(service.features.sms.notificationMessageId("test.sms",1001,"Match"))
   provider.rows.add(Sms(11,"Other sender","Match",1000))
   assertNull(service.features.sms.notificationMessageId("test.sms",1000,"Match"))
   shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_SMS)
   assertNull(service.features.sms.notificationMessageId("test.sms",1000,"Match"))
  } finally { controller.destroy();shadowOf(Looper.getMainLooper()).idle() }
 }
}
