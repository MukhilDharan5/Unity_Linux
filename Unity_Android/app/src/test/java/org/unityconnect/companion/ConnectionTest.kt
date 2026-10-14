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
}
