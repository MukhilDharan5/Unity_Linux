package org.unityconnect.companion

import android.app.role.RoleManager
import android.content.Context
import android.net.Uri
import android.telecom.Call
import android.telecom.CallAudioState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneCallTest {
 private fun call(properties: Int = 0): Call {
  val details = Shadow.newInstanceOf(Call.Details::class.java)
  ReflectionHelpers.setField(details, "mHandle", Uri.parse("tel:15550123"))
  ReflectionHelpers.setField(details, "mCallerDisplayName", "Alex")
  ReflectionHelpers.setField(details, "mConnectTimeMillis", 1791900000000L)
  ReflectionHelpers.setField(details, "mCallCapabilities", Call.Details.CAPABILITY_HOLD)
  ReflectionHelpers.setField(details, "mCallProperties", properties)
  val call = Shadow.newInstanceOf(Call::class.java)
  ReflectionHelpers.setField(call, "mDetails", details)
  ReflectionHelpers.setField(call, "mState", Call.STATE_ACTIVE)
  ReflectionHelpers.setField(call, "mCallbackRecords", java.util.concurrent.CopyOnWriteArrayList<Any>())
  return call
 }
 @Test fun optionalPhoneRoleIsOffUntilChosenByTheUser() {
  val context = RuntimeEnvironment.getApplication()
  val role = context.getSystemService(RoleManager::class.java)
  assertFalse(UnityInCallService.enabled(context))
  shadowOf(role).addHeldRole(RoleManager.ROLE_DIALER)
  assertTrue(UnityInCallService.enabled(context))
  shadowOf(role).removeHeldRole(RoleManager.ROLE_DIALER)
  assertFalse(UnityInCallService.enabled(context))
 }
 @Test fun speakerphoneUsesTelecomAndCallIdentitySurvivesAudioChanges() {
  val controller = Robolectric.buildService(UnityInCallService::class.java).create()
  val service = controller.get(); val call = call()
  try {
   service.onCallAdded(call)
   val mask = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER or CallAudioState.ROUTE_BLUETOOTH
   shadowOf(service).setSupportedRouteMask(mask)
   service.onCallAudioStateChanged(CallAudioState(false, CallAudioState.ROUTE_EARPIECE, mask))
   val initial = service.snapshot().getJSONArray("calls").getJSONObject(0)
   assertEquals(1791900000000L, initial.getLong("connect_time"))
   assertTrue(initial.getBoolean("can_hold"))
   assertEquals("android_telecom", service.snapshot().getString("backend"))
   service.control("route", JSONObject().put("route", "speaker"))
   assertEquals(CallAudioState.ROUTE_SPEAKER, service.callAudioState.route)
   service.onCallAudioStateChanged(CallAudioState(true, CallAudioState.ROUTE_SPEAKER, mask))
   assertEquals("speaker", service.snapshot().getString("route"))
   assertTrue(service.snapshot().getBoolean("muted"))
   assertEquals(initial.getString("id"), service.snapshot().getJSONArray("calls").getJSONObject(0).getString("id"))
   assertThrows(IllegalStateException::class.java) { service.control("route", JSONObject().put("route", "wired")) }
   assertThrows(IllegalStateException::class.java) { service.control("hangup", JSONObject().put("call_id", "ab".repeat(16))) }
   service.onCallRemoved(call)
   assertEquals(0, service.snapshot().getJSONArray("calls").length())
   assertThrows(IllegalStateException::class.java) { service.control("route", JSONObject().put("route", "speaker")) }
  } finally { controller.destroy() }
  assertNull(UnityInCallService.instance)
 }
 @Test fun phoneCallUiStillWorksWithoutAComputerOrCallSharing() {
  val controller = Robolectric.buildService(UnityInCallService::class.java).create()
  try {
   val service = controller.get(); val call = call(); service.onCallAdded(call)
   assertNull(CompanionService.instance)
   val activity = Robolectric.buildActivity(PhoneCallActivity::class.java).setup()
   assertFalse(activity.get().isFinishing)
   service.onCallRemoved(call)
   shadowOf(android.os.Looper.getMainLooper()).idle()
   assertTrue(activity.get().isFinishing)
   activity.pause().stop().destroy()
  } finally { controller.destroy() }
 }
 @Test fun emergencyAndRevokedRoleDoNotAllowRemoteAudioChanges() {
  val controller = Robolectric.buildService(UnityInCallService::class.java).create()
  try {
   val service = controller.get(); service.onCallAdded(call(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL))
   assertThrows(IllegalStateException::class.java) { service.control("route", JSONObject().put("route", "speaker"), remote = true) }
  } finally { controller.destroy() }
  val companion = Robolectric.buildService(CompanionService::class.java).create()
  try {
   val service = companion.get()
   service.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("enable.call_control", true).commit()
   shadowOf(service.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY, true)
   shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE)
   assertThrows(IllegalStateException::class.java) { service.features.callControl.command("route", JSONObject().put("route", "speaker")) }
  } finally { companion.destroy() }
 }
}
