package org.unityconnect.companion

import android.content.Intent
import android.os.BatteryManager
import org.json.JSONObject
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatteryTelemetryTest {
 private fun discharge() = BatteryReading(60, "discharging", false, 3800, -500000, chargeUah = 3000000)

 @Test fun convertsActualSensorUnitsToWattsAndRuntime() {
  val value = BatteryEstimator().sample(discharge(), 0)
  assertEquals(3.8, value.getDouble("voltage_v"), .000001)
  assertEquals(-500.0, value.getDouble("current_ma"), .000001)
  assertEquals(1.9, value.getDouble("power_w"), .000001)
  assertEquals(21600L, value.getLong("remaining_seconds"))
  assertEquals("empty", value.getString("estimate_kind"))
  assertEquals("charge_counter", value.getString("estimate_source"))
 }
 @Test fun smoothsRuntimeWhilePowerTracksCurrentDraw() {
  val estimator = BatteryEstimator()
  estimator.sample(discharge(), 0)
  val value = estimator.sample(discharge().copy(currentUa = -1000000), 30000)
  assertEquals(3.8, value.getDouble("power_w"), .000001)
  assertEquals(14400L, value.getLong("remaining_seconds"))
  val avg = BatteryEstimator().sample(discharge().copy(averageCurrentUa = -1000000), 0)
  assertEquals(10800L, avg.getLong("remaining_seconds"))
  val zeroAverage = BatteryEstimator().sample(discharge().copy(averageCurrentUa = 0), 0)
  assertEquals(21600L, zeroAverage.getLong("remaining_seconds"))
 }
 @Test fun prefersAndroidChargingEstimateAndFallsBackToMeasuredChargeRate() {
  val reading = BatteryReading(50, "charging", true, 4000, 1000000, chargeUah = 2500000, chargeTimeMs = 2700000)
  val system = BatteryEstimator().sample(reading, 0)
  assertEquals(-4.0, system.getDouble("power_w"), .000001)
  assertEquals(2700L, system.getLong("remaining_seconds"))
  assertEquals("android", system.getString("estimate_source"))
  val calculated = BatteryEstimator().sample(reading.copy(chargeTimeMs = -1), 0)
  assertEquals(9000L, calculated.getLong("remaining_seconds"))
  assertEquals("full", calculated.getString("estimate_kind"))
 }
 @Test fun unsupportedSensorsStayUnknownUntilEnoughBatteryTrendIsMeasured() {
  val estimator = BatteryEstimator()
  val reading = BatteryReading(80, "discharging", false)
  val first = estimator.sample(reading, 0)
  for (key in listOf("power_w", "current_ma", "voltage_v", "remaining_seconds")) assertTrue(first.isNull(key))
  assertEquals("collecting", first.getString("estimate_state"))
  var last = first
  for (n in 1..20) last = estimator.sample(reading.copy(level = 80 - n / 10), n * 30000L)
  assertEquals("battery_trend", last.getString("estimate_source"))
  assertEquals(23400L, last.getLong("remaining_seconds"))
  assertTrue(last.isNull("power_w"))
 }
 @Test fun chargingTrendWorksWithoutAssumedCapacity() {
  val estimator = BatteryEstimator()
  var value = JSONObject()
  for (n in 0..20) value = estimator.sample(BatteryReading(50 + n / 10, "charging", true), n * 30000L)
  assertEquals(14400L, value.getLong("remaining_seconds"))
  assertEquals("battery_trend", value.getString("estimate_source"))
 }
 @Test fun chargerChangesLongGapsAndGaugeCorrectionsDiscardOldEstimates() {
  val estimator = BatteryEstimator()
  estimator.sample(discharge(), 0)
  val charging = estimator.sample(BatteryReading(50, "charging", true, 4000, 1000000, chargeUah = 2500000), 30000)
  assertEquals(9000L, charging.getLong("remaining_seconds"))
  val fresh = estimator.sample(discharge().copy(currentUa = -1000000), 60000)
  assertEquals(10800L, fresh.getLong("remaining_seconds"))
  val gap = estimator.sample(discharge(), 300000)
  assertEquals(21600L, gap.getLong("remaining_seconds"))
  estimator.reset()
  for (n in 0..20) estimator.sample(BatteryReading(80 - n / 10, "discharging", false), n * 30000L)
  assertTrue(estimator.sample(BatteryReading(81, "discharging", false), 630000).isNull("remaining_seconds"))
 }
 @Test fun pausedChargingZeroCurrentAndFullBatteryAreNotInfiniteEstimates() {
  val paused = BatteryEstimator().sample(BatteryReading(80, "not_charging", true, 4200, 0), 0)
  assertTrue(paused.isNull("remaining_seconds"))
  assertEquals("unavailable", paused.getString("estimate_state"))
  assertTrue(BatteryEstimator().sample(discharge().copy(currentUa = 0), 0).isNull("remaining_seconds"))
  val full = BatteryEstimator().sample(BatteryReading(100, "full", true), 0)
  assertEquals(0L, full.getLong("remaining_seconds"))
  assertEquals("available", full.getString("estimate_state"))
  val drainingOnCharger = BatteryEstimator().sample(BatteryReading(50, "charging", true, 4000, -500000, chargeTimeMs = 10000), 0)
  assertTrue(drainingOnCharger.isNull("remaining_seconds"))
  assertEquals("unavailable", drainingOnCharger.getString("estimate_state"))
 }
 @Test fun rejectsImpossibleOrUnsupportedReadingsWithoutGuessingPhoneCapacity() {
  for (current in listOf(Int.MIN_VALUE, Int.MAX_VALUE, -20000001, 500000)) {
   val value = BatteryEstimator().sample(discharge().copy(currentUa = current), 0)
   assertTrue(value.isNull("power_w")); assertTrue(value.isNull("remaining_seconds"))
  }
  assertTrue(BatteryEstimator().sample(discharge().copy(voltageMv = 0), 0).isNull("power_w"))
  assertTrue(BatteryEstimator().sample(discharge().copy(chargeUah = 0), 0).isNull("remaining_seconds"))
  assertThrows(IllegalArgumentException::class.java) { BatteryEstimator().sample(discharge().copy(level = 101), 0) }
 }
 @Test fun refreshesSensorsEveryThirtySecondsAndStopsOnDisconnect() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try {
   val gauge = shadowOf(service.getSystemService(BatteryManager::class.java))
   gauge.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -500000)
   gauge.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, Int.MIN_VALUE)
   gauge.setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 3000000)
   val connected = TestConnection(service)
   val queue = connected.lan.messages
   @Suppress("DEPRECATION")
   service.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 60)
    .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_VOLTAGE, 3800)
    .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
   shadowOf(Looper.getMainLooper()).idle(); queue.clear()
   service.features.battery.watch()
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
   assertEquals(1.9, connected.lan.next { it.optString("feature") == "battery" }.getJSONObject("data").getDouble("power_w"), .000001)
   gauge.setIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -1000000)
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
   assertEquals(3.8, connected.lan.next { it.optString("feature") == "battery" }.getJSONObject("data").getDouble("power_w"), .000001)
   connected.close(); queue.clear()
   shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
   assertTrue(queue.isEmpty())
  } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
}
