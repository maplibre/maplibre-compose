package org.maplibre.compose.map

/**
 * Settings that exist on some platforms only. Each platform adds extension properties on the public
 * builders to read and write them.
 */
internal expect class PlatformRenderOptions() {
  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int
}

internal expect class PlatformDebugOverlays() {
  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int
}

internal expect class PlatformTileLodOptions() {
  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  companion object {
    val Performance: PlatformTileLodOptions
    val HighDetail: PlatformTileLodOptions
  }
}

/** Compose UI settings that exist on some platforms only. */
internal expect class PlatformUiOptions() {
  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int
}
