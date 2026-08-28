package org.maplibre.compose.mlnffi

import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent

/**
 * A still-image render target the platform creates without a UI: a graphics context for a
 * session-owned texture that MapLibre renders into and the snapshot reads back.
 */
internal interface MlnFfiSnapshotTarget : AutoCloseable {
  /** The backend the session renders with, for the engine's core bookkeeping. */
  val backend: MapRenderBackend

  /**
   * Attaches the still-image session to [map]. Called once, on the snapshot thread; that thread
   * owns the returned session and every later call on it, including close.
   */
  fun attach(map: MapHandle, extent: RenderTargetExtent): RenderSessionHandle
}

/**
 * Creates the platform's still-image target, or throws [UnsupportedOperationException] when the
 * packaged runtime has no backend this platform can drive without a UI.
 */
internal expect fun createSnapshotTarget(): MlnFfiSnapshotTarget

/**
 * Throws [UnsupportedOperationException] when [createSnapshotTarget] would. Does not allocate a
 * graphics context.
 */
internal expect fun requireSnapshotSupported()
