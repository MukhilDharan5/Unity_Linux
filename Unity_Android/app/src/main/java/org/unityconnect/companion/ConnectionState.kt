package org.unityconnect.companion

/** Public local UI state; phone transport and service lifetime stay independent. */
enum class ConnectionPhase { DISCONNECTED, CONNECTING, PAIRING, CONNECTED, RECONNECTING }

data class ConnectionState(val phase: ConnectionPhase, val detail: String) {
 val title: String get() = when (phase) {
  ConnectionPhase.DISCONNECTED -> "Not connected"
  ConnectionPhase.CONNECTING -> "Connecting…"
  ConnectionPhase.PAIRING -> "Waiting for approval"
  ConnectionPhase.CONNECTED -> "Connected securely"
  ConnectionPhase.RECONNECTING -> "Reconnecting…"
 }
 companion object {
  val disconnected = ConnectionState(ConnectionPhase.DISCONNECTED, "Connect to your computer on the same local network.")
 }
}

data class TransferState(val busy: Boolean = false, val title: String = "No transfer in progress", val failed: Boolean = false)
