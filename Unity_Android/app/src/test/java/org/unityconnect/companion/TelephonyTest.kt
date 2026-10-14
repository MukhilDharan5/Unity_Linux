package org.unityconnect.companion

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.Call
import android.telecom.TelecomManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelephonyTest {
 @Test fun onlySafeTelephoneNumbersCanBeRemotelyDialed() {
  assertEquals("+15551234567", phoneNumber("+1 (555) 123-4567"))
  for (number in listOf("+", "++12", "12+3", "*123#", "tel:123", "１２３", "")) assertThrows(IllegalArgumentException::class.java) { phoneNumber(number) }
 }
 @Test fun multipartReportsHandleDuplicatesOutOfOrderDeliveryAndFailures() {
  val report = SmsProgress(2)
  assertEquals("submitted_to_modem", report.update(0,false,true,null))
  assertEquals("submitted_to_modem", report.update(0,false,true,null))
  assertEquals("sent", report.update(1,false,true,null))
  assertEquals("sent", report.update(0,true,true,32))
  assertEquals("sent", report.update(0,true,true,0))
  assertEquals("delivered", report.update(1,true,true,0))
  assertEquals("delivered", report.update(0,false,true,null))
  assertEquals("delivery_failed", SmsProgress(1).update(0,true,true,64))
  assertEquals("failed", SmsProgress(1).update(0,false,false,null))
  assertThrows(IllegalArgumentException::class.java) { report.update(2,false,true,null) }
 }
 @Test fun callStateAndCapabilitiesPreventActionsOnTheWrongCall() {
  assertEquals("ringing", callState(Call.STATE_RINGING))
  requireCallAction("answer",Call.STATE_RINGING,0)
  requireCallAction("hold",Call.STATE_ACTIVE,Call.Details.CAPABILITY_HOLD)
  assertThrows(IllegalStateException::class.java) { requireCallAction("answer",Call.STATE_ACTIVE,0) }
  assertThrows(IllegalStateException::class.java) { requireCallAction("hold",Call.STATE_ACTIVE,0) }
  assertThrows(IllegalStateException::class.java) { requireCallAction("dtmf",Call.STATE_HOLDING,0) }
  assertThrows(IllegalStateException::class.java) { requireCallAction("hangup",Call.STATE_DISCONNECTED,0) }
 }
 @Test fun bluetoothCallControlWorksWithoutTheOptionalDefaultDialerRole() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try {
   assertEquals("disabled", service.features.callControl.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { service.features.callControl.command("dial",JSONObject().put("number","12345")) }
   shadowOf(service.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY,true)
   service.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("enable.call_control",true).commit()
   assertEquals("permission_required",service.features.callControl.capability().getString("status"))
   shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CALL_PHONE)
   assertFalse(dialerRoleHeld(service))
   assertEquals("permission_required",service.features.callControl.capability().getString("status"))
   shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_PHONE_STATE)
   assertEquals("available",service.features.callControl.capability().getString("status"))
   assertFalse(dialerRoleHeld(service))
   shadowOf(service.getSystemService(TelecomManager::class.java)).setCallPhonePermission(true)
   assertEquals("submitted", service.features.callControl.command("dial",JSONObject().put("number","12345")).getString("state"))
   assertEquals("tel:12345", shadowOf(service.getSystemService(TelecomManager::class.java)).onlyOutgoingCall.address.toString())
   assertThrows(IllegalStateException::class.java) { service.features.callControl.command("history",JSONObject()) }
   shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_PHONE_STATE)
   assertEquals("permission_required",service.features.callControl.capability().getString("status"))
   val dialer = Robolectric.buildActivity(DialerActivity::class.java,Intent(Intent.ACTION_DIAL,Uri.parse("tel:12345"))).setup()
   dialer.pause().stop().destroy()
  } finally { controller.destroy() }
 }
 private fun dialerRoleHeld(context: Context) = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
 @Test fun optionalPhoneRoleHasAnOsProtectedCallServiceWithoutPrivilegedPermissions() {
  val context = org.robolectric.RuntimeEnvironment.getApplication()
  val dialer = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_DIAL, Uri.parse("tel:12345")), 0)
  assertTrue(dialer.any { it.activityInfo.packageName == context.packageName })
  val services = context.packageManager.queryIntentServices(Intent("android.telecom.InCallService"), 0)
  val phone = services.single { it.serviceInfo.packageName == context.packageName }
  assertEquals("android.permission.BIND_INCALL_SERVICE", phone.serviceInfo.permission)
  assertTrue(phone.serviceInfo.exported)
  assertFalse(dialerRoleHeld(context))
  val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
  assertFalse(permissions.contains(Manifest.permission.MANAGE_ONGOING_CALLS))
  assertFalse(permissions.contains("android.permission.MODIFY_PHONE_STATE"))
 }
}
