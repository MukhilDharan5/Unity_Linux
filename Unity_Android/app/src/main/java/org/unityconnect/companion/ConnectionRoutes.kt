package org.unityconnect.companion

internal enum class LinkKind { BLUETOOTH, LAN }

/** Live authenticated channels only. Removing a link never removes its sibling. */
internal class ConnectionRoutes<T : Any> {
 private val links = mutableMapOf<LinkKind, T>()
 @Synchronized fun put(kind: LinkKind, value: T) { links[kind] = value }
 @Synchronized fun remove(kind: LinkKind, value: T) { if (links[kind] === value) links.remove(kind) }
 @Synchronized fun contains(value: T) = links.values.any { it === value }
 @Synchronized fun values() = links.values.toList()
 @Synchronized fun kinds() = links.keys.toSet()
 @Synchronized fun select(feature: String): T? {
  val bulk = feature in setOf("files", "photos", "browsing", "audio", "screen")
  return if (bulk) links[LinkKind.LAN] ?: links[LinkKind.BLUETOOTH] else links[LinkKind.BLUETOOTH] ?: links[LinkKind.LAN]
 }
}
