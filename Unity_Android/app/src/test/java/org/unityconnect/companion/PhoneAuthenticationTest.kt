package org.unityconnect.companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Looper
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.security.keystore.KeyProperties
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBiometricPrompt
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
class PhoneAuthenticationTest {
 @Test fun androidAndPamShareTheSameTranscriptAndSignatureFormat() {
  val fixture=JSONObject(javaClass.getResourceAsStream("/pam-challenge.json")!!.bufferedReader().readText())
  val bytes=AuthenticationChallenge(fixture.getJSONObject("challenge")).bytes()
  assertEquals(fixture.getString("transcript_sha256"),Protocol.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
  val der=Protocol.unhex("3059301306072a8648ce3d020106082a8648ce3d030107034200"+fixture.getString("public_key"))
  val key=java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(der))
  assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(key);update(bytes) }.verify(Protocol.unhex(fixture.getString("signature"))))
 }
 private val request="ab".repeat(16)
 private fun idle()=shadowOf(Looper.getMainLooper()).idle()
 private fun challenge(phone:String="12".repeat(16))=JSONObject().put("version",1).put("machine_id","34".repeat(32))
  .put("computer_fingerprint","56".repeat(32)).put("computer","Laptop").put("uid",1000).put("username","alice")
  .put("service","sudo").put("tty","/dev/pts/0").put("remote_user","alice").put("device_id",phone)
  .put("nonce","78".repeat(32)).put("expires",System.currentTimeMillis()/1000+45)
 private class Credential:AuthenticationCredential {
  val keys=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  override fun present()=true
  override fun create() {}
  lateinit var lastSignature:Signature
  override fun signature()=Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private) }.also { lastSignature=it }
  override fun fingerprint()="90".repeat(32)
  override fun enrollmentBytes()="fixture enrollment".toByteArray()
  override fun enrollment(signed:ByteArray)=JSONObject().put("signature",Protocol.hex(signed))
  override fun reset() {}
 }
 // Robolectric 4.17's success helper deliberately returns a null CryptoObject. Supply the
 // framework's real result shape here, with the actual software test Signature operation.
 private fun callback():BiometricPrompt.AuthenticationCallback {
  val session=ReflectionHelpers.getStaticField<Any>(ShadowBiometricPrompt::class.java,"currentAuthenticateSession")
  return ReflectionHelpers.getField(session,"callback")
 }
 private fun completeCrypto(callback:BiometricPrompt.AuthenticationCallback,credential:Credential) {
  val result=ReflectionHelpers.callConstructor(BiometricPrompt.AuthenticationResult::class.java,
   ClassParameter.from(BiometricPrompt.CryptoObject::class.java,BiometricPrompt.CryptoObject(credential.lastSignature)),
   ClassParameter.from(Int::class.javaPrimitiveType,2))
  callback.onAuthenticationSucceeded(result)
 }
 private fun configure(service:CompanionService) {
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.authentication",true).putString("fingerprint","56".repeat(32)).commit()
  org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBiometricManager>(service.getSystemService(BiometricManager::class.java)).apply { setCanAuthenticate(true);setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG) }
 }
 private fun installedCredential(service:CompanionService):Credential {
  configure(service)
  return Credential().also { ReflectionHelpers.setField(service.features.authentication,"credential",it) }
 }
 private fun notification(service:CompanionService)=service.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id=="authentication".hashCode() }.notification
 @Test fun notificationOpensTheExactApprovalDirectlyWithMainAlreadyInTheBackground() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  val credential=installedCredential(service);val auth=service.features.authentication
  val main=Robolectric.buildActivity(MainActivity::class.java).setup().pause().stop()
  try {
   val data=challenge(service.credentials.id);val replies=mutableListOf<String>()
   auth.receive(request,data,Any()) { replies.add(it) };idle()
   val notice=notification(service);val target=shadowOf(notice.contentIntent).savedIntent
   assertEquals(PhoneAuthenticationActivity::class.java.name,target.component?.className)
   assertEquals(request,target.getStringExtra("request"));assertNotNull(target.data)
   assertTrue(notice.contentIntent.isImmutable)
   assertEquals(0,notice.flags and Notification.FLAG_AUTO_CANCEL)
   assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT!=0)
   notice.contentIntent.send();idle();assertEquals(request,auth.requestId());assertTrue(replies.isEmpty())
   val activity=Robolectric.buildActivity(PhoneAuthenticationActivity::class.java,target).setup()
   try {
    assertFalse(activity.get().isFinishing)
    assertTrue(activity.get().findViewById<Button>(R.id.authentication_approve).isEnabled)
    assertNull(ShadowBiometricPrompt.getCurrentPrompt())
    activity.get().findViewById<Button>(R.id.authentication_approve).performClick()
    completeCrypto(callback(),credential);ShadowBiometricPrompt.cancelCurrentSession();idle()
    assertTrue(activity.get().isFinishing);assertEquals(1,replies.size)
    assertTrue(Signature.getInstance("SHA256withECDSA").apply {initVerify(credential.keys.public);update(AuthenticationChallenge(data).bytes())}.verify(Protocol.unhex(replies.single())))
    assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.none {it.id=="authentication".hashCode()})
   } finally {activity.pause().stop().destroy()}
  } finally {main.destroy();controller.destroy();idle()}
 }
 @Test fun staleNotificationCannotTurnIntoApprovalForANewerRequest() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get();installedCredential(service)
  val auth=service.features.authentication
  try {
   auth.receive(request,challenge(service.credentials.id),Any()) {};idle()
   val old=notification(service).contentIntent;val original=shadowOf(old).savedIntent
   auth.cancel("Request withdrawn")
   val next="cd".repeat(16)
   auth.receive(next,challenge(service.credentials.id).put("nonce","90".repeat(32)),Any()) {};idle()
   val current=notification(service).contentIntent
   assertNotEquals(old,current)
   assertEquals(request,shadowOf(old).savedIntent.getStringExtra("request"))
   assertEquals(next,shadowOf(current).savedIntent.getStringExtra("request"))
   val activity=Robolectric.buildActivity(PhoneAuthenticationActivity::class.java,original).setup()
   try {
    assertFalse(activity.get().findViewById<Button>(R.id.authentication_approve).isEnabled)
    assertTrue(activity.get().findViewById<TextView>(R.id.authentication_request).text.contains("ended"))
    activity.get().finish();activity.pause().stop().destroy()
    assertEquals(next,auth.requestId());assertNull(ShadowBiometricPrompt.getCurrentPrompt())
   } finally {if(!activity.get().isDestroyed)activity.destroy()}
  } finally {controller.destroy();idle()}
 }
 @Test fun incomingNotificationRequestWinsOverRestoredOldActivityStateAndWarmActivityIntents() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get();installedCredential(service)
  val auth=service.features.authentication
  try {
   val next="cd".repeat(16)
   auth.receive(next,challenge(service.credentials.id),Any()) {};idle()
   val target=shadowOf(notification(service).contentIntent).savedIntent
   val saved=Bundle().apply {putString("request",request)}
   val activity=Robolectric.buildActivity(PhoneAuthenticationActivity::class.java,target).create(saved).start().restoreInstanceState(saved).resume().visible()
   try {
    assertTrue(activity.get().findViewById<Button>(R.id.authentication_approve).isEnabled)
    auth.cancel("Request ended")
    val newest="ef".repeat(16)
    auth.receive(newest,challenge(service.credentials.id).put("nonce","90".repeat(32)).put("username","bob"),Any()) {};idle()
    val newTarget=shadowOf(notification(service).contentIntent).savedIntent
    activity.newIntent(newTarget);idle()
    assertTrue(activity.get().findViewById<Button>(R.id.authentication_approve).isEnabled)
    assertTrue(activity.get().findViewById<TextView>(R.id.authentication_request).text.contains("bob"))
    activity.newIntent(newTarget);idle()
    assertEquals(newest,auth.requestId());assertNull(ShadowBiometricPrompt.getCurrentPrompt())
   } finally {activity.pause().stop().destroy()}
  } finally {controller.destroy();idle()}
 }
 @Test fun aBiometricErrorLeavesAnExplanationOnTheApprovalPageInsteadOfClosingIt() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get();installedCredential(service)
  val auth=service.features.authentication;val replies=mutableListOf<String>()
  auth.receive(request,challenge(service.credentials.id),Any()) {replies.add(it)};idle()
  val activity=Robolectric.buildActivity(PhoneAuthenticationActivity::class.java,Intent().putExtra("request",request)).setup()
  try {
   activity.get().findViewById<Button>(R.id.authentication_approve).performClick()
   callback().onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,"Try again later");idle()
   assertFalse(activity.get().isFinishing)
   assertFalse(activity.get().findViewById<Button>(R.id.authentication_approve).isEnabled)
   assertTrue(activity.get().findViewById<TextView>(R.id.authentication_request).text.contains("Try again later"))
   assertEquals(listOf(""),replies)
  } finally {activity.pause().stop().destroy();controller.destroy();idle()}
 }
 @Test fun restoredMainHandlesALegacyNotificationOnceAndFeaturesCanReopenThePendingRequest() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get();installedCredential(service)
  val auth=service.features.authentication
  auth.receive(request,challenge(service.credentials.id),Any()) {};idle()
  val intent=Intent().putExtra("phone_action","authentication").putExtra("request",request)
  val saved=Bundle().apply {putString("page","FEATURES")}
  val main=Robolectric.buildActivity(MainActivity::class.java,intent).create(saved).start().restoreInstanceState(saved).resume().visible()
  try {
   val opened=shadowOf(main.get()).nextStartedActivity
   assertEquals(PhoneAuthenticationActivity::class.java.name,opened?.component?.className)
   assertEquals(request,opened.getStringExtra("request"))
   assertNull(main.get().intent.getStringExtra("phone_action"))
   main.recreate();assertNull(shadowOf(main.get()).nextStartedActivity)
   main.get().findViewById<android.view.View>(R.id.open_authentication).performClick()
   assertEquals(request,shadowOf(main.get()).nextStartedActivity.getStringExtra("request"))
   assertEquals(request,auth.requestId());assertNull(ShadowBiometricPrompt.getCurrentPrompt())
  } finally {main.pause().stop().destroy();controller.destroy();idle()}
 }
 @Test fun expiredNotificationAndUnavailableServiceStillOpenAnExplicitEndedRequestPage() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get();installedCredential(service)
  val replies=mutableListOf<String>()
  service.features.authentication.receive(request,challenge(service.credentials.id).put("expires",System.currentTimeMillis()/1000+1),Any()) {replies.add(it)};idle()
  val target=shadowOf(notification(service).contentIntent).savedIntent
  shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
  val expired=Robolectric.buildActivity(PhoneAuthenticationActivity::class.java,target).setup()
  try {
   assertFalse(expired.get().isFinishing)
   assertFalse(expired.get().findViewById<Button>(R.id.authentication_approve).isEnabled)
   assertTrue(expired.get().findViewById<TextView>(R.id.authentication_request).text.contains("timed out"))
   assertEquals(listOf(""),replies)
  } finally {expired.pause().stop().destroy();controller.destroy();idle()}
  val unavailable=Robolectric.buildActivity(PhoneAuthenticationActivity::class.java,target).setup()
  try {
   assertFalse(unavailable.get().isFinishing)
   assertFalse(unavailable.get().findViewById<Button>(R.id.authentication_approve).isEnabled)
   assertTrue(unavailable.get().findViewById<TextView>(R.id.authentication_request).text.contains("ended"))
  } finally {unavailable.pause().stop().destroy();idle()}
 }
 @Test fun keyRequiresEveryUseStrongBiometricsAndEnrollmentInvalidationWithoutPhonePinFallback() {
  val spec=FingerprintCredential.parameters("fixture")
  assertEquals(KeyProperties.PURPOSE_SIGN,spec.purposes)
  assertEquals(listOf(KeyProperties.DIGEST_SHA256),spec.digests.toList())
  assertTrue(spec.isUserAuthenticationRequired)
  assertEquals(0,spec.userAuthenticationValidityDurationSeconds)
  assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG,spec.userAuthenticationType)
  assertTrue(spec.isInvalidatedByBiometricEnrollment)
 }
 @Test fun actualCryptoPromptSignsOnlyTheDisplayedChallengeAndOnlyOnce() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  val activity=Robolectric.buildActivity(Activity::class.java).setup()
  try {
   val service=controller.get();configure(service)
   val credential=Credential();val module=PhoneAuthentication(service,credential)
   val data=challenge(service.credentials.id);val replies=mutableListOf<String>();val origin=Any()
   module.receive(request,data,origin) { replies.add(it) };idle()
   assertEquals(request,module.requestId());assertTrue(replies.isEmpty())
   assertNull(ShadowBiometricPrompt.getCurrentPrompt()) // Receipt never launches biometrics in the background.
   module.authenticate(activity.get(),request)
   assertNotNull(ShadowBiometricPrompt.getCurrentPrompt());assertTrue(replies.isEmpty())
   completeCrypto(callback(),credential);ShadowBiometricPrompt.cancelCurrentSession();idle()
   assertEquals(1,replies.size);assertTrue(module.lastError,replies.single().isNotEmpty());assertNull(module.requestId())
   val verify=Signature.getInstance("SHA256withECDSA").apply { initVerify(credential.keys.public);update(AuthenticationChallenge(data).bytes()) }
   assertTrue(verify.verify(Protocol.unhex(replies.single())))
   module.receive("cd".repeat(16),data,origin) { replies.add(it) };idle()
   assertEquals("",replies.last());assertNull(module.requestId())
  } finally { activity.pause().stop().destroy();controller.destroy() }
 }
 @Test fun aBooleanBiometricSuccessWithoutTheCryptoObjectNeverAuthenticates() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  val activity=Robolectric.buildActivity(Activity::class.java).setup()
  try {
   val service=controller.get();configure(service);val module=PhoneAuthentication(service,Credential())
   val replies=mutableListOf<String>();module.receive(request,challenge(service.credentials.id),Any()) { replies.add(it) };idle()
   module.authenticate(activity.get(),request)
   ShadowBiometricPrompt.authenticateCurrentSessionSuccessfully();idle()
   assertEquals(listOf(""),replies);assertNull(module.requestId())
  } finally { activity.pause().stop().destroy();controller.destroy() }
 }
 @Test fun wrongConnectionCannotCancelOrCompleteAPendingPromptAndDisconnectWithdrawsIt() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  val activity=Robolectric.buildActivity(Activity::class.java).setup()
  try {
   val service=controller.get();configure(service);val credential=Credential();val module=PhoneAuthentication(service,credential)
   val origin=Any();val replies=mutableListOf<String>()
   module.receive(request,challenge(service.credentials.id),origin) { replies.add(it) };idle()
   module.receive(request,null,Any()) { fail("Wrong connection supplied a response") };idle()
   module.disconnected(Any());idle();assertEquals(request,module.requestId())
   module.authenticate(activity.get(),request)
   val stale=callback()
   module.disconnected(origin);idle()
   assertEquals(listOf(""),replies);assertNull(module.requestId())
   completeCrypto(stale,credential);idle()
   assertEquals(listOf(""),replies)
  } finally { activity.pause().stop().destroy();controller.destroy() }
 }
 @Test fun alteredComputerPhoneExpiredAndDisabledRequestsNeverOpenAPrompt() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  try {
   val service=controller.get();configure(service);val module=PhoneAuthentication(service,Credential())
   for (data in listOf(challenge().put("computer_fingerprint","00".repeat(32)),challenge(),
    challenge(service.credentials.id).put("expires",System.currentTimeMillis()/1000-1),
    challenge(service.credentials.id).put("username","alice\nsudo"))) {
    var reply:String?=null;module.receive(request,data,Any()) { reply=it };idle()
    assertEquals("",reply);assertNull(module.requestId());assertNull(ShadowBiometricPrompt.getCurrentPrompt())
   }
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.authentication",false).commit()
   var reply:String?=null;module.receive(request,challenge(service.credentials.id),Any()) {reply=it};idle()
   assertEquals("",reply)
  } finally { controller.destroy() }
 }
 @Test fun wireRepliesStayOnTheirOriginalConnectionAndDefaultIsDisabled() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  try { TestConnection(controller.get(),true).use { fixture ->
   assertEquals("disabled",controller.get().features.authentication.capability().getString("status"))
   fixture.bluetooth.send(JSONObject().put("type","auth_request").put("id",request).put("challenge",challenge(controller.get().credentials.id)))
   fixture.await { idle();fixture.bluetooth.messages.any { it.optString("type")=="auth_response" } }
   val response=fixture.bluetooth.next { it.optString("type")=="auth_response" }
   assertEquals(request,response.getString("id"));assertEquals("",response.getString("signature"))
   assertFalse(fixture.lan.messages.any { it.optString("type")=="auth_response" })
  } } finally { controller.destroy() }
 }
}
