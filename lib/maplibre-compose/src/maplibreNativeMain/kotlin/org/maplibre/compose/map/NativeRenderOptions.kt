package org.maplibre.compose.map

/** The camera projection MapLibre Native renders with. */
public var RenderOptions.Builder.cameraProjection: CameraProjection
  get() = platform.cameraProjection
  set(value) {
    platform = platform.copy(cameraProjection = value)
  }

/** The camera projection MapLibre Native renders with. */
public val RenderOptions.cameraProjection: CameraProjection
  get() = platform.cameraProjection

/** Draws the time each tile was last updated. */
public var DebugOverlays.Builder.tileTimestamps: Boolean
  get() = platform.tileTimestamps
  set(value) {
    platform = platform.copy(tileTimestamps = value)
  }

/** Draws the time each tile was last updated. */
public val DebugOverlays.tileTimestamps: Boolean
  get() = platform.tileTimestamps

/** Draws tile parse state on each tile. */
public var DebugOverlays.Builder.tileParseStatus: Boolean
  get() = platform.tileParseStatus
  set(value) {
    platform = platform.copy(tileParseStatus = value)
  }

/** Draws tile parse state on each tile. */
public val DebugOverlays.tileParseStatus: Boolean
  get() = platform.tileParseStatus

internal actual data class PlatformRenderOptions(val cameraProjection: CameraProjection) {
  actual constructor() : this(CameraProjection.Perspective)
}

internal actual data class PlatformDebugOverlays(
  val tileTimestamps: Boolean,
  val tileParseStatus: Boolean,
) {
  actual constructor() : this(tileTimestamps = false, tileParseStatus = false)
}
