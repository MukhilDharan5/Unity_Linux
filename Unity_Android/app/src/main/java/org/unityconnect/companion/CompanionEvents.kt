package org.unityconnect.companion

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local state invalidation. No exported receiver or private payload. */
internal object CompanionEvents {
 private val observers = CopyOnWriteArraySet<() -> Unit>()
 private val main = Handler(Looper.getMainLooper())
 private val scheduled = AtomicBoolean(false)
 fun observe(observer: () -> Unit) { observers.add(observer) }
 fun remove(observer: () -> Unit) { observers.remove(observer) }
 fun changed() {
  if (scheduled.compareAndSet(false, true)) main.post {
   scheduled.set(false)
   observers.forEach { it() }
  }
 }
}
