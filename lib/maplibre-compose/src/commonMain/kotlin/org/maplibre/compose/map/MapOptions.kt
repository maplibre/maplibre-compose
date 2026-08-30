package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * Configures one temporary map presentation.
 *
 * Each field's companion object provides presets. [GestureOptions] is the same on every platform.
 * [RenderOptions] and [TileLodOptions] still differ by backend, so a custom configuration of those
 * two is written in expect/actual code.
 */
@Immutable
public data class MapPresentationOptions(
  val cameraPadding: PaddingValues = PaddingValues(0.dp),
  val zoomRange: ClosedRange<Float> = 0f..20f,
  val pitchRange: ClosedRange<Float> = 0f..60f,
  val boundingBox: BoundingBox? = null,
  val renderOptions: RenderOptions = RenderOptions.Standard,
  val gestureOptions: GestureOptions = GestureOptions.Standard,
  val tileLodOptions: TileLodOptions = TileLodOptions.Standard,
)

/** Callbacks from one temporary map presentation. */
@Immutable
public data class MapPresentationCallbacks(
  val onClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  val onLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  val onFrame: (framesPerSecond: Double) -> Unit = {},
)
