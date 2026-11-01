package org.unityconnect.companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

internal data class AuthenticationChallenge(val json: JSONObject) {
 val computer = json.getString("computer")
 val username = json.getString("username")
 val service = json.getString("service")
 val tty = json.getString("tty")
 val expires = json.getLong("expires")
 init {
  require(json.getInt("version")==1 && json.getLong("uid") in 1000..4294967294L) { "Invalid authentication request" }
  for (field in listOf("machine_id","computer_fingerprint","nonce")) require(hex(json.getString(field),64)) { "Invalid authentication identity" }
  require(hex(json.getString("device_id"),32)) { "Invalid phone identity" }
  for ((field,limit) in listOf("computer" to 64,"username" to 64,"service" to 64,"tty" to 128,"remote_user" to 64)) {
   val value=json.getString(field); require(value.length<=limit && value.all { it.code in 32..126 }) { "Invalid authentication context" }
  }
  require(computer.isNotEmpty() && username.isNotEmpty() && service in setOf("sudo","sudo-i","login","gdm-password","gdm-fingerprint","polkit-1","unity-pam-test")) { "Unsupported authentication request" }
 }
 fun bytes(): ByteArray = ("unity-pam-v1\n"+listOf(json.getString("machine_id"),json.getString("computer_fingerprint"),computer,
  json.getLong("uid").toString(),username,service,tty,json.getString("remote_user"),json.getString("device_id"),json.getString("nonce"),expires.toString(),"1").joinToString("\n")+"\n").toByteArray(Charsets.US_ASCII)
 companion object { private fun hex(s:String,n:Int)=s.length==n && s.all { it in '0'..'9' || it in 'a'..'f' } }
}

internal interface AuthenticationCredential {
 fun present():Boolean
 fun create()
 fun signature():Signature
 fun fingerprint():String
 fun enrollmentBytes():ByteArray
 fun enrollment(signed:ByteArray):JSONObject
 fun reset()
}
/** Only public key bytes leave Android Keystore. Every Signature operation requires a new strong biometric. */
internal class FingerprintCredential(private val service: CompanionService) : AuthenticationCredential {
 private val store get()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
 private val pin get()=service.getSharedPreferences("settings",0).getString("fingerprint","").orEmpty().lowercase().filterNot { it==':' || it.isWhitespace() }
 private val alias get()="unity-pam-v1-$pin"
 override fun present() = runCatching { store.containsAlias(alias) }.getOrDefault(false)
 override fun create() {
  require(pin.length==64 && Protocol.unhex(pin).size==32) { "Pair with your computer first" }
  if (!present()) {
   KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply { initialize(parameters(alias)) }.generateKeyPair()
  }
  try { privateKey() } catch (e:Exception) { store.deleteEntry(alias);throw e }
 }
 private fun privateKey(): PrivateKey {
  val key=store.getKey(alias,null) as? PrivateKey ?: error("Create your fingerprint credential first")
  val info=KeyFactory.getInstance(key.algorithm,"AndroidKeyStore").getKeySpec(key,KeyInfo::class.java)
  @Suppress("DEPRECATION")
  check(info.isInsideSecureHardware && info.isUserAuthenticationRequired && info.isUserAuthenticationRequirementEnforcedBySecureHardware && info.userAuthenticationValidityDurationSeconds in -1..0 && info.isInvalidatedByBiometricEnrollment) { "A hardware-protected, per-use biometric key is required" }
  return key
 }
 override fun signature() = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey()) }
 fun publicKey(): ByteArray {
  val key=store.getCertificate(alias).publicKey as ECPublicKey
  fun coordinate(value:java.math.BigInteger): ByteArray { val bytes=value.toByteArray(); return bytes.takeLast(32).toByteArray().let { ByteArray(32-it.size)+it } }
  return byteArrayOf(4)+coordinate(key.w.affineX)+coordinate(key.w.affineY)
 }
 override fun fingerprint() = Protocol.hex(MessageDigest.getInstance("SHA-256").digest(publicKey()))
 override fun enrollmentBytes() = "unity-pam-enroll-v1\n${service.credentials.id}\n$pin\n${Protocol.hex(publicKey())}\n".toByteArray(Charsets.US_ASCII)
 override fun enrollment(signed:ByteArray) = JSONObject().put("version",1).put("device_id",service.credentials.id).put("computer_fingerprint",pin)
  .put("public_key",Protocol.hex(publicKey())).put("key_sha256",fingerprint()).put("signature",Protocol.hex(signed))
 override fun reset() { if (present()) store.deleteEntry(alias) }
 companion object {
  internal fun parameters(alias:String):KeyGenParameterSpec {
   val builder=KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
   if (Build.VERSION.SDK_INT>=30) builder.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
   else @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1)
   return builder.build()
  }
 }
}

class PhoneAuthentication internal constructor(service: CompanionService,
 internal val credential:AuthenticationCredential=FingerprintCredential(service)) : Module(service,"authentication") {
 companion object {
  const val CHANNEL="unity.authentication"
  internal val NOTIFICATION="authentication".hashCode()
 }
 private data class Pending(val id:String,val challenge:AuthenticationChallenge,val origin:Any,val deadline:Long,val reply:(String)->Unit)
 private var pending:Pending?=null
 private var signal:CancellationSignal?=null
 private val main=Handler(Looper.getMainLooper())
 private val seen=linkedSetOf<String>()
 private var ended:Pair<String,String>?=null
 internal var lastError=""
  private set
 override fun capability():JSONObject {
  if (!enabled()) return status()
  val manager=service.getSystemService(BiometricManager::class.java)
  val available=if (Build.VERSION.SDK_INT>=30) manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) else @Suppress("DEPRECATION") manager.canAuthenticate()
  return if (available!=BiometricManager.BIOMETRIC_SUCCESS) cap(id,"user_action_required","Enroll a strong fingerprint in Android settings")
   else if (!credential.present()) cap(id,"user_action_required","Create and export your fingerprint credential to the laptop")
   else cap(id,reason="Fresh fingerprint approval for each laptop authentication; laptop password remains available")
 }
 override fun command(action:String,data:JSONObject):JSONObject = error("Authentication requires its dedicated paired-session protocol")
 internal fun receive(request:String,data:JSONObject?,origin:Any,reply:(String)->Unit) {
  require(Protocol.validId(request))
  main.post {
   try {
    if (data==null) { if (pending?.id==request && pending?.origin===origin) cancel(); return@post }
    check(enabled() && capability().optString("status")=="available") { "Set up phone fingerprint approval first" }
    check(pending==null) { "Another authentication request is awaiting approval" }
    val challenge=AuthenticationChallenge(JSONObject(data.toString()))
    val pin=service.getSharedPreferences("settings",0).getString("fingerprint","").orEmpty().lowercase().filterNot { it==':' || it.isWhitespace() }
    require(challenge.json.getString("computer_fingerprint")==pin && challenge.json.getString("device_id")==service.credentials.id) { "Authentication computer or phone does not match" }
    val seconds=challenge.expires-System.currentTimeMillis()/1000
    require(seconds in 1..60) { "Authentication expired; use your laptop password or retry" }
    require(seen.add(challenge.json.getString("nonce"))) { "Repeated authentication challenge" }
    while (seen.size>128) seen.remove(seen.first())
    val value=Pending(request,challenge,origin,SystemClock.elapsedRealtime()+seconds*1000,reply); pending=value;lastError=""
    notify(value)
    service.uiChanged()
    main.postDelayed({ if (pending===value) cancel("Fingerprint approval timed out") },seconds*1000)
   } catch (e:Exception) { lastError=(e.message ?: "Authentication unavailable").take(180);reply("");service.uiChanged() }
  }
 }
 internal fun description():String? = pending?.challenge?.let { "${it.computer}\nAccount: ${it.username}\nRequest: ${it.service}\nTerminal: ${it.tty.ifBlank { "Login screen" }}" }
 internal fun requestId():String?=pending?.id
 internal fun endedReason(request:String):String?=ended?.takeIf {it.first==request}?.second
 private fun notify(value:Pending) {
  val manager=service.getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL,"Laptop fingerprint approval",NotificationManager.IMPORTANCE_HIGH).apply {
   description="Review laptop login and sudo requests";lockscreenVisibility=Notification.VISIBILITY_PRIVATE
  })
  val open=PendingIntent.getActivity(service,0,PhoneAuthenticationActivity.reviewIntent(service,value.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  manager.notify(NOTIFICATION,Notification.Builder(service,CHANNEL).setSmallIcon(R.drawable.ic_shield)
   .setContentTitle("${value.challenge.computer}: approve ${value.challenge.service} for ${value.challenge.username}")
   .setContentText("Tap to review and approve with your fingerprint").setContentIntent(open)
   .setCategory(Notification.CATEGORY_EVENT).setVisibility(Notification.VISIBILITY_PRIVATE)
   .setOnlyAlertOnce(true).setOngoing(true).setAutoCancel(false)
   .setTimeoutAfter((value.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)).build())
 }
 internal fun authenticate(activity:Activity,request:String) {
  val value=checkNotNull(pending) { "Authentication request is no longer pending" }
  check(value.id==request && enabled() && value.deadline>SystemClock.elapsedRealtime()) { "Authentication request expired or was disabled" }
  val signature=credential.signature()
  prompt(activity,"Approve ${value.challenge.service}","${value.challenge.username} on ${value.challenge.computer}",signature,
   { unlocked ->
    if (pending!==value || !enabled() || value.deadline<=SystemClock.elapsedRealtime()) { cancel("Authentication expired"); return@prompt }
    try { unlocked.update(value.challenge.bytes()); val signed=unlocked.sign(); ended=value.id to "This request was already approved.";pending=null;signal=null;clearNotification();value.reply(Protocol.hex(signed));service.uiChanged();activity.finish() }
    catch (e:Exception) { cancel(e.message ?: "Fingerprint signing failed") }
   }, { reason -> cancel(reason) })
 }
 internal fun enroll(activity:Activity,complete:(JSONObject)->Unit) {
  check(enabled()) { "Enable phone fingerprint approval in Features first" }
  check(pending==null) { "Finish or cancel the current laptop authentication first" }
  credential.create(); val signature=credential.signature()
  prompt(activity,"Set up laptop fingerprint approval","Confirm with your phone fingerprint",signature,
   { unlocked -> try { unlocked.update(credential.enrollmentBytes());val signed=unlocked.sign();signal=null;complete(credential.enrollment(signed));service.features.publish() } catch (e:Exception) { lastError=e.message ?: "Credential setup failed";service.uiChanged() } },
   { reason -> lastError=reason;service.uiChanged() })
 }
 private fun prompt(activity:Activity,title:String,subtitle:String,signature:Signature,success:(Signature)->Unit,failure:(String)->Unit) {
  signal?.cancel();val cancellation=CancellationSignal();signal=cancellation
  val builder=BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle).setDescription("Approve only a request you started on your laptop. Your laptop password remains available.")
   .setNegativeButton("Use laptop password",activity.mainExecutor) { _,_ -> if (signal===cancellation) failure("Phone approval declined") }
  if (Build.VERSION.SDK_INT>=30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
  builder.build().authenticate(BiometricPrompt.CryptoObject(signature),cancellation,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
   override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
    if (signal!==cancellation || cancellation.isCanceled) return
    val unlocked=result.cryptoObject?.signature ?: run { failure("No biometric signing operation");return }
    if (unlocked!==signature) { failure("Biometric operation changed");return };success(unlocked)
   }
   override fun onAuthenticationError(code:Int,message:CharSequence) { if (signal===cancellation) failure(message.toString().take(180)) }
  })
 }
 private fun clearNotification() { service.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION) }
 internal fun cancel(reason:String="Phone approval cancelled") {
  val value=pending;pending=null;val old=signal;signal=null;old?.cancel();clearNotification()
  if (value!=null) { lastError=reason;ended=value.id to reason;value.reply("");service.uiChanged() }
 }
 internal fun disconnected(origin:Any) { main.post { if (pending?.origin===origin) cancel("Authentication connection lost") } }
 internal fun settingsChanged() { main.post { if (!enabled()) cancel("Phone fingerprint approval disabled") } }
}

/** A notification opens this non-exported activity; background requests never launch a biometric prompt. */
class PhoneAuthenticationActivity:AdwaitaActivity() {
 companion object {
  private const val REVIEW="org.unityconnect.companion.REVIEW_AUTHENTICATION"
  internal fun reviewIntent(context:Context,request:String)=Intent(context,PhoneAuthenticationActivity::class.java)
   .setAction(REVIEW).setData(Uri.parse("unity-authentication://request/$request")).putExtra("request",request)
   .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
 }
 private var export:JSONObject?=null
 private var request:String?=null
 private val module get()=CompanionService.instance?.features?.authentication
 private var approve:Button?=null
 private var requestStatus:TextView?=null
 private val changed:()->Unit={ refreshRequest() }
 override fun onCreate(saved:Bundle?) {
  super.onCreate(saved)
  request=intent.getStringExtra("request") ?: if (intent.action==REVIEW) "" else saved?.getString("request")
  export=if (request==null) saved?.getString("export")?.let { JSONObject(it) } else null
  render()
 }
 override fun onNewIntent(intent:Intent) {
  super.onNewIntent(intent);setIntent(intent)
  val incoming=intent.getStringExtra("request") ?: if (intent.action==REVIEW) "" else null
  if (incoming!=request) { request=incoming;export=null;render() } else refreshRequest()
 }
 private fun render() {
  approve=null;requestStatus=null
  val ui=AdwaitaUi(this);val page=contentPage("Fingerprint approval")
  val intro=ui.section(page,"Laptop fingerprint approval")
  ui.addRow(intro,ui.row("Your fingerprint stays on this phone","Approve a laptop request with a fresh fingerprint. Your laptop password remains available.",R.drawable.ic_shield).view)
  val auth=module
  if (request!=null) {
   val review=ui.section(page,"Review request","Only approve a request you started on your laptop.")
   requestStatus=ui.label("",16f).apply { id=R.id.authentication_request;setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(20));setTextIsSelectable(true) };review.addView(requestStatus)
   approve=ui.action(review,"Approve with fingerprint",suggested=true) { safe { checkNotNull(module) { "Connect to your laptop and retry the request" }.authenticate(this@PhoneAuthenticationActivity,request!!) } }.apply { id=R.id.authentication_approve }
   ui.action(review,"Use laptop password") { module?.let { if (request==it.requestId()) it.cancel("Phone approval declined") };finish() }.id=R.id.authentication_decline
   refreshRequest()
  } else {
   if (auth==null) { ui.addRow(intro,ui.row("Connect to your laptop","Open Unity and connect to your laptop first.",R.drawable.ic_computer).view);return }
   val setup=ui.section(page,"Set up approval","Create a phone fingerprint credential, export it to your laptop, then finish the laptop’s administrator setup.")
   val status=ui.label("",14f,secondary=true).apply { id=R.id.authentication_credential;setTextIsSelectable(true);setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(20)) };setup.addView(status)
   fun showKey() { status.text=runCatching { "Credential fingerprint:\n${auth.credential.fingerprint()}" }.getOrDefault("No fingerprint credential yet") }
   showKey()
   ui.action(setup,"Create and export credential",suggested=true) { safe { auth.enroll(this@PhoneAuthenticationActivity) { export=it;showKey();startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Unity-fingerprint-credential.json"),70) } } }.id=R.id.authentication_export
   val reset=ui.section(page,"Reset credential","Resetting ends the current laptop enrollment. Export and enroll a new credential to use approval again.")
   ui.action(reset,"Reset fingerprint credential",destructive=true) {
    ui.dialog(destructive=true).setTitle("Reset fingerprint credential?").setMessage("The current laptop enrollment will stop working. Your laptop password remains available. Export and enroll the new credential again.").setNegativeButton("Cancel",null).setPositiveButton("Reset") { _,_ -> safe { auth.cancel();auth.credential.reset();export=null;showKey() } }.show()
   }.id=R.id.authentication_reset
  }
 }
 private fun refreshRequest() {
  if (request==null) return
  val auth=module;val current=request==auth?.requestId()
  approve?.isEnabled=current
  requestStatus?.text=if (current) auth?.description().orEmpty() else listOfNotNull(auth?.endedReason(request!!),"This authentication request ended. Use your laptop password or retry.").joinToString("\n\n")
 }
 override fun onStart() { super.onStart();CompanionEvents.observe(changed);refreshRequest() }
 override fun onStop() { CompanionEvents.remove(changed);super.onStop() }
 private fun safe(action:()->Unit) { try { action() } catch (e:Exception) {
  if (request!=null && module?.requestId()==request) module?.cancel(e.message ?: "Fingerprint approval unavailable")
  AdwaitaUi(this).dialog().setTitle("Fingerprint approval unavailable").setMessage(e.message ?: "Fingerprint approval unavailable").setPositiveButton("OK",null).show()
 } }
 override fun onSaveInstanceState(out:Bundle) { out.putString("request",request);out.putString("export",export?.toString());super.onSaveInstanceState(out) }
 @Deprecated("Android activity result") override fun onActivityResult(code:Int,result:Int,data:Intent?) {
  super.onActivityResult(code,result,data)
  if (code==70 && result==RESULT_OK && data?.data!=null) safe {
   val value=checkNotNull(export);contentResolver.openOutputStream(data.data!!,"wt")!!.use { it.write((value.toString(2)+"\n").toByteArray()) }
   AdwaitaUi(this).dialog().setTitle("Credential exported").setMessage("Compare its fingerprint during the laptop’s administrator setup to enable authentication.").setPositiveButton("OK",null).show()
  }
 }
 override fun onDestroy() { if (isFinishing && request!=null && module?.requestId()==request) module?.cancel();super.onDestroy() }
}
