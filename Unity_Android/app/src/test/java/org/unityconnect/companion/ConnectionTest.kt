package org.unityconnect.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionTest {
 @Test fun authenticatedHotspotAddressReplacesAStaleLiveLanWithoutReplacingBluetooth() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  try { TestConnection(controller.get(),true,savedHost="192.0.2.10").use { fixture ->
   fun update(vararg hosts:String)=JSONObject().put("type","event").put("feature","connectivity")
    .put("data",JSONObject().put("version",1).put("origin","desktop").put("encrypted",true).put("tls","TLSv1.3")
     .put("lan_endpoints",org.json.JSONArray().apply { hosts.forEach { put(JSONObject().put("host",it).put("port",1764)) } }))
   fixture.bluetooth.send(update("10.197.29.13"))
   fixture.await { fixture.lanDestinations.lastOrNull()==LanAddress("10.197.29.13",1764) &&
    controller.get().connection.transportLabel=="Bluetooth + Wi-Fi / LAN" }
   assertFalse(fixture.bluetooth.closed);assertEquals(1,fixture.attempts.get())
   val attempts=fixture.lanAttempts.get()
   fixture.bluetooth.send(update("10.197.29.13","192.0.2.20"))
   Thread.sleep(100)
   assertEquals(attempts,fixture.lanAttempts.get());assertFalse(fixture.lan.closed)
  } } finally { controller.destroy() }
 }
 @Test fun hotspotRouteChangeCancelsStaleCandidatesAndKeepsBluetoothConnected() {
  val controller=Robolectric.buildService(CompanionService::class.java).create()
  val service=controller.get()
  val bluetooth=TestConnection.Peer();val lan=TestConnection.Peer()
  val firstOpen=java.util.concurrent.CountDownLatch(1)
  val cancelled=java.util.concurrent.CountDownLatch(1)
  val ports=java.util.concurrent.CopyOnWriteArrayList<Int>()
  val bluetoothAttempts=java.util.concurrent.atomic.AtomicInteger()
  service.connection.stop()
  service.getSharedPreferences("settings",0).edit().putString("host","test.invalid").putInt("port",17161)
   .putString("transport","both").putString("bluetooth_address","AA:BB:CC:DD:EE:FF")
   .putString("fingerprint","ab".repeat(32)).commit()
  val connection=Connection(service,{"cd".repeat(32)},{},{endpoint,opening ->
   if(endpoint.bluetooth) {
    bluetoothAttempts.incrementAndGet();bluetooth.channel().also(opening)
   } else {
    ports.add(endpoint.port)
    if(ports.size==1) {
     opening(java.io.Closeable { cancelled.countDown() });firstOpen.countDown()
     check(cancelled.await(3,java.util.concurrent.TimeUnit.SECONDS)) { "Opening socket was not cancelled" }
     throw java.io.IOException("Old hotspot route closed")
    }
    lan.channel().also(opening)
   }
  })
  service.connection=connection
  try {
   connection.start();assertTrue(firstOpen.await(5,java.util.concurrent.TimeUnit.SECONDS))
   connection.retry(LinkKind.LAN,invalidate=true)
   val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
   while(connection.transportLabel!="Bluetooth + Wi-Fi / LAN" || !connection.ready) {
    check(System.nanoTime()<deadline) { "Hotspot connection did not recover" };Thread.sleep(10)
   }
   assertEquals(listOf(17161,17161),ports.toList())
   assertEquals(1,bluetoothAttempts.get());assertFalse(bluetooth.closed)
  } finally { controller.destroy() }
 }
 @Test fun radioRecoveryRetriesImmediatelyWithoutReplacingTheOtherLinkOrDuplicatingStart() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try { TestConnection(service, true).use { fixture ->
   service.connection.start(); service.connection.start()
   Thread.sleep(100)
   assertEquals(1, fixture.lanAttempts.get()); assertEquals(1, fixture.attempts.get())
   fixture.rejectLan = true; fixture.lan.disconnect()
   fixture.await { fixture.lanAttempts.get() >= 2 && service.connection.transportLabel == "Bluetooth" }
   fixture.rejectLan = false
   repeat(10) { service.connection.retry(LinkKind.LAN) }
   fixture.await { service.connection.transportLabel == "Bluetooth + Wi-Fi / LAN" }
   assertFalse(fixture.bluetooth.closed); assertTrue(service.connection.ready)
   assertEquals(1, fixture.attempts.get())
   val attempts = fixture.lanAttempts.get()
   service.connection.stop(); service.connection.retry(LinkKind.LAN); service.connection.start()
   Thread.sleep(100); assertEquals(attempts, fixture.lanAttempts.get()); assertFalse(service.connection.ready)
  } } finally { controller.destroy() }
 }
 @Test fun bothLinksStayOpenRouteByWorkloadAndAnswerTheirOwnHeartbeat() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try { TestConnection(service, true).use { fixture ->
   for (feature in listOf("battery", "clipboard", "media", "remote_input", "notifications", "ringer")) {
    assertTrue(service.connection.send(JSONObject().put("type", "event").put("feature", feature).put("data", JSONObject())))
    assertEquals(feature, fixture.bluetooth.next { it.optString("feature") == feature }.getString("feature"))
   }
   for (feature in listOf("files", "photos", "browsing", "audio", "screen")) {
    assertTrue(service.connection.send(JSONObject().put("type", "event").put("feature", feature).put("data", JSONObject())))
    assertEquals(feature, fixture.lan.next { it.optString("feature") == feature }.getString("feature"))
   }
   for (peer in listOf(fixture.lan, fixture.bluetooth)) {
    peer.send(JSONObject().put("type", "ping")); peer.next { it.optString("type") == "pong" }
    assertFalse(peer.closed)
   }
   val id = "12".repeat(16)
   fixture.lan.send(JSONObject().put("type", "command").put("id", id).put("feature", "unknown").put("action", "test").put("data", JSONObject()))
   assertFalse(fixture.lan.next { it.optString("id") == id }.getBoolean("ok"))
   assertFalse(fixture.bluetooth.messages.any { it.optString("id") == id })
  } } finally { controller.destroy() }
 }
 @Test fun losingBluetoothKeepsLanReadyAndReconnectsWithoutClosingLan() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try { TestConnection(service, true).use { fixture ->
   val file = service.connection.fileSender()
   fixture.rejectBluetooth = true; fixture.bluetooth.disconnect()
   fixture.await { service.connection.transportLabel == "Wi-Fi / LAN" }
   assertTrue(service.connection.ready); assertFalse(fixture.lan.closed)
   assertTrue(service.connection.send(JSONObject().put("type", "event").put("feature", "battery").put("data", JSONObject())))
   fixture.lan.next { it.optString("feature") == "battery" }
   file(JSONObject().put("phase", "chunk"))
   fixture.lan.next { it.optString("feature") == "files" }
   fixture.rejectBluetooth = false
   fixture.await { service.connection.transportLabel == "Bluetooth + Wi-Fi / LAN" }
   assertFalse(fixture.lan.closed); assertTrue(fixture.attempts.get() >= 2)
  } } finally { controller.destroy() }
 }
 @Test fun losingLanDoesNotMovePartialFilesButNewTransfersUseBluetooth() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try { TestConnection(service, true).use { fixture ->
   val original = service.connection.fileSender()
   fixture.lan.disconnect()
   fixture.await { service.connection.transportLabel == "Bluetooth" }
   assertTrue(service.connection.ready); assertFalse(fixture.bluetooth.closed)
   assertThrows(IllegalStateException::class.java) { original(JSONObject().put("phase", "chunk")) }
   service.connection.fileSender()(JSONObject().put("phase", "begin"))
   fixture.bluetooth.next { it.optString("feature") == "files" }
   fixture.await { service.connection.transportLabel == "Bluetooth + Wi-Fi / LAN" }
   assertThrows(IllegalStateException::class.java) { original(JSONObject().put("phase", "chunk")) }
  } } finally { controller.destroy() }
 }
 @Test fun routeRemovalCannotRemoveAReplacementOrTheOtherTransport() {
  val routes = ConnectionRoutes<Any>(); val lan = Any(); val bt = Any(); val newer = Any()
  routes.put(LinkKind.LAN, lan); routes.put(LinkKind.BLUETOOTH, bt)
  assertSame(bt, routes.select("clipboard")); assertSame(lan, routes.select("files"))
  routes.put(LinkKind.BLUETOOTH, newer); routes.remove(LinkKind.BLUETOOTH, bt)
  assertSame(newer, routes.select("battery")); assertSame(lan, routes.select("audio"))
  routes.remove(LinkKind.LAN, lan); assertSame(newer, routes.select("screen"))
 }
 @Test fun playbackErrorsReturnOnTheirOriginalLinkAfterStartupSuccess() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try { TestConnection(service, true).use { fixture ->
   val commandId = "ab".repeat(16)
   val reporter = java.util.concurrent.atomic.AtomicReference<(String) -> Unit>()
   val release = java.util.concurrent.CountDownLatch(1)
   @Suppress("UNCHECKED_CAST")
   val modules = service.features.modules as MutableMap<String, Feature>
   modules["audio"] = object : Feature {
    override val id = "audio"
    override fun capability() = cap(id)
    override fun command(action: String, data: JSONObject): JSONObject {
     service.connection.streamEndpoint(data.getString("request_id"), id)
     reporter.set(service.connection.streamFailureReporter(data.getString("request_id")))
     check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
     return JSONObject().put("state", "receiving")
    }
   }
   fixture.lan.send(JSONObject().put("type", "command").put("id", commandId).put("feature", "audio")
    .put("action", "start").put("data", JSONObject().put("request_id", commandId)))
   fixture.await { reporter.get() != null || fixture.lan.messages.any { it.optString("id") == commandId } }
   assertNotNull(fixture.lan.messages.toString(), reporter.get()); release.countDown()
   assertTrue(fixture.lan.next { it.optString("id") == commandId }.getBoolean("ok"))
   reporter.get().invoke("Phone audio playback failed (-6)")
   val failed = fixture.lan.next { it.optString("id") == commandId }
   assertFalse(failed.getBoolean("ok")); assertEquals("Phone audio playback failed (-6)", failed.getString("error"))
   assertFalse(fixture.bluetooth.messages.any { it.optString("id") == commandId })
   fixture.lan.disconnect(); fixture.await { service.connection.transportLabel == "Bluetooth" }
   reporter.get().invoke("Old stream disconnected")
   assertFalse(fixture.bluetooth.messages.any { it.optString("id") == commandId })
  } } finally { controller.destroy() }
 }
}
