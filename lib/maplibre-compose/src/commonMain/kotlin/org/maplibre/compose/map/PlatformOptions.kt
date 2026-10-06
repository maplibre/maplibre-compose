package org.maplibre.compose.map

/**
 * Settings that exist on some platforms only. Each platform adds extension properties on the public
 * builders to read and write them.
 */
internal expect class PlatformRenderOptions() {
  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  /** The platform settings as `name to value` pairs for the owner's `toString`. */
  val fields: List<Pair<String, Any?>>
}

internal expect class PlatformDebugOverlays() {
  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  /** The platform settings as `name to value` pairs for the owner's `toString`. */
  val fields: List<Pair<String, Any?>>
}

/** Compose UI settings that exist on some platforms only. */
internal expect class PlatformUiOptions() {
  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  /** The platform settings as `name to value` pairs for the owner's `toString`. */
  val fields: List<Pair<String, Any?>>
}
