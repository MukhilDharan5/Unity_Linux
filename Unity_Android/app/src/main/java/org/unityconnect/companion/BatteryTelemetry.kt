package org.unityconnect.companion

import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToLong

internal const val MAX_BATTERY_SECONDS = 30L * 24 * 60 * 60

/** Android units: microamps, microamp-hours and millivolts. No assumed capacity. */
internal data class BatteryReading(
 val level: Int,
 val status: String,
 val plugged: Boolean,
 val voltageMv: Int = 0,
 val currentUa: Int = Int.MIN_VALUE,
 val averageCurrentUa: Int = Int.MIN_VALUE,
 val chargeUah: Int = Int.MIN_VALUE,
 val chargeTimeMs: Long = -1
)

internal class BatteryEstimator {
 private data class Current(val time: Long, val value: Double)
 private data class Level(val time: Long, val value: Int)
 private val currents = ArrayDeque<Current>()
 private val levels = ArrayDeque<Level>()
 private var mode: String? = null
 private var lastTime: Long? = null
 @Synchronized fun reset() { currents.clear(); levels.clear(); mode = null; lastTime = null }
 private fun current(value: Int): Double? = value.takeIf { it != Int.MIN_VALUE && it in -20_000_000..20_000_000 }?.toDouble()
 private fun seconds(value: Double): Long? = value.takeIf { it.isFinite() && it >= 0 && it <= MAX_BATTERY_SECONDS }?.roundToLong()

 @Synchronized fun sample(reading: BatteryReading, elapsedMs: Long): JSONObject {
  require(reading.level in 0..100)
  require(reading.status in listOf("charging", "discharging", "full", "not_charging", "unknown"))
  val charging = reading.status in listOf("charging", "full")
  val nextMode = "${reading.status}:${reading.plugged}"
  // Charger/status changes, clock resets and long sampling gaps invalidate history.
  if (mode != nextMode || lastTime?.let { elapsedMs < it || elapsedMs - it > 120_000 } == true) reset()
  mode = nextMode; lastTime = elapsedMs
  val raw = current(reading.currentUa) ?: current(reading.averageCurrentUa)
  // A positive unplugged reading contradicts Android's documented sign convention.
  val instant = raw?.takeIf { reading.plugged || it <= 0 }
  val volts = reading.voltageMv.takeIf { it in 1000..10000 }?.div(1000.0)
  // Positive watts mean battery draw; negative watts mean net battery charging.
  val watts = if (volts != null && instant != null) -instant * volts / 1_000_000 else null
  val rate = sequenceOf(current(reading.averageCurrentUa), instant).filterNotNull().firstOrNull {
   (reading.status == "charging" && reading.plugged && it >= 5000) ||
    (reading.status == "discharging" && !reading.plugged && it <= -5000)
  }
  while (currents.isNotEmpty() && elapsedMs - currents.first.time > 120_000) currents.removeFirst()
  if (rate == null) currents.clear()
  else {
   if (currents.isNotEmpty() && elapsedMs - currents.last.time < 15_000) currents.removeLast()
   currents.addLast(Current(elapsedMs, rate))
  }
  val average = if (currents.isEmpty()) null else currents.map { it.value }.average()
  val active = (reading.status == "charging" && reading.plugged) || (reading.status == "discharging" && !reading.plugged)
  if (active) {
   val previous = levels.peekLast()
   if (previous != null && ((charging && reading.level < previous.value) || (!charging && reading.level > previous.value))) levels.clear()
   // Keep at most two hours, bounded even if battery broadcasts arrive rapidly.
   while (levels.isNotEmpty() && elapsedMs - levels.first.time > 7_200_000) levels.removeFirst()
   if (levels.isEmpty() || elapsedMs - levels.last.time >= 15_000) levels.addLast(Level(elapsedMs, reading.level))
  } else levels.clear()
  var remaining: Long? = null
  var source = "none"
  val kind = if (charging || reading.plugged) "full" else "empty"
  if (reading.status == "full" && reading.plugged) {
   remaining = 0; source = "android"
  } else if (active && !(charging && instant != null && instant < 0)) {
   if (charging && reading.chargeTimeMs >= 0) {
    remaining = seconds(reading.chargeTimeMs / 1000.0)
    if (remaining != null) source = "android"
   }
   val charge = reading.chargeUah.takeIf { it in 1..30_000_000 }
   if (remaining == null && average != null && charge != null) {
    val amount = if (charging && reading.level > 0) charge.toDouble() * (100 - reading.level) / reading.level
     else if (!charging) charge.toDouble() else null
    if (amount != null) {
     remaining = seconds(amount / abs(average) * 3600)
     if (remaining != null) source = "charge_counter"
    }
   }
   if (remaining == null && levels.size >= 2) {
    val first = levels.first; val last = levels.last
    val span = (last.time - first.time) / 1000.0
    val change = if (charging) last.value - first.value else first.value - last.value
    // Integer percentage readings need enough elapsed time and movement to be useful.
    if (span >= 600 && change >= 2) {
     remaining = seconds((if (charging) 100 - reading.level else reading.level) * span / change)
     if (remaining != null) source = "battery_trend"
    }
   }
  }
  return JSONObject().put("level", reading.level).put("charging", charging).put("plugged", reading.plugged)
   .put("status", reading.status).put("voltage_v", volts ?: JSONObject.NULL)
   .put("current_ma", instant?.div(1000) ?: JSONObject.NULL).put("power_w", watts ?: JSONObject.NULL)
   .put("remaining_seconds", remaining ?: JSONObject.NULL).put("estimate_kind", kind).put("estimate_source", source)
   .put("estimate_state", if (remaining != null) "available"
    else if (active && !(charging && instant != null && instant < 0)) "collecting" else "unavailable")
 }
}
