package org.maplibre.compose.map

import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.util.DpPadding

/** Engine padding in logical pixels, retaining the engine's double precision. */
internal data class CameraPadding(
  val top: Double = 0.0,
  val left: Double = 0.0,
  val bottom: Double = 0.0,
  val right: Double = 0.0,
) {
  operator fun plus(padding: DpPadding): CameraPadding =
    CameraPadding(
      top + padding.top.value,
      left + padding.left.value,
      bottom + padding.bottom.value,
      right + padding.right.value,
    )

  /** Fractional insets can round-trip with residue; engines reject negative padding. */
  fun relativeTo(insets: CameraPadding): DpPadding =
    DpPadding(
      left = (left - insets.left).coerceAtLeast(0.0).dp,
      top = (top - insets.top).coerceAtLeast(0.0).dp,
      right = (right - insets.right).coerceAtLeast(0.0).dp,
      bottom = (bottom - insets.bottom).coerceAtLeast(0.0).dp,
    )
}

/** Layers layout-owned insets outside app-owned camera padding. Main only. */
internal class ViewportInsets {
  private var requested = CameraPadding()
  var applied = CameraPadding()
    private set

  private var pendingCameraPadding: DpPadding? = null

  fun set(insets: DpPadding): Boolean {
    val next = CameraPadding() + insets
    if (requested == next) return false
    requested = next
    return true
  }

  /** A new engine cannot apply camera framing before it has a real viewport. */
  fun prepareCamera(requested: CameraPosition?): CameraPosition? {
    applied = CameraPadding()
    pendingCameraPadding = requested?.padding?.takeUnless { it == DpPadding.Zero }
    return requested?.copy(padding = DpPadding.Zero)
  }

  fun cameraForAssignment(position: CameraPosition, hasViewport: Boolean): CameraPosition {
    if (hasViewport) return position
    pendingCameraPadding = position.padding.takeUnless { it == DpPadding.Zero }
    return position.copy(padding = DpPadding.Zero)
  }

  /** Returns effective engine padding only when an inset or deferred camera padding changed. */
  fun apply(beforeChange: () -> Unit, currentCamera: () -> CameraPosition): CameraPadding? {
    if (requested == applied && pendingCameraPadding == null) return null
    beforeChange()
    val current = currentCamera()
    val effective = requested + (pendingCameraPadding ?: current.padding)
    applied = requested
    pendingCameraPadding = null
    return effective
  }
}
