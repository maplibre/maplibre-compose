package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapStyleState

/**
 * A full-map [BoxScope]. Normal Compose sizing and alignment do not apply any implicit insets. Use
 * [Controls] to arrange controls inside the unobstructed region, and [AtPosition] or
 * [TowardsPosition] for geographic placement.
 */
@LayoutScopeMarker
@Stable
public interface MapOverlayScope : BoxScope {
  /** The logical map that this overlay belongs to. */
  public val mapState: MapState

  /** The style state of the map that this overlay belongs to. */
  public val style: MapStyleState
    get() = mapState.style

  /** The camera padding supplied to the map composable, without waiting for a rendered frame. */
  public val cameraPadding: PaddingValues
}

/**
 * A box for map controls. Applies [contentPadding], then any remaining [contentWindowInsets], and
 * [MapOverlay.Spacing]. Consuming the padding before applying the insets takes the larger amount on
 * each edge instead of adding them. Insets already consumed outside the map stay consumed. Override
 * [contentPadding] when camera padding does not describe the controls' available region. Geographic
 * placements inside this box still refer to the original map.
 */
@Composable
public fun MapOverlayScope.Controls(
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = cameraPadding,
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
  content: @Composable MapOverlayScope.() -> Unit,
) {
  val parent = this as MapOverlayScopeImpl
  Box(
    modifier
      .fillMaxSize()
      .padding(contentPadding)
      .consumeWindowInsets(contentPadding)
      .windowInsetsPadding(contentWindowInsets)
      .padding(MapOverlay.Spacing)
  ) {
    val scope =
      remember(parent, this) {
        MapOverlayScopeImpl(parent.mapState, parent.cameraPadding, this, parent.coordinates)
      }
    content(scope)
  }
}

/** Draws [overlay] into this scope. A supplied map overlay replaces the built-in controls. */
@Composable
@UiComposable
public fun MapOverlayScope.include(overlay: MapOverlay) {
  overlay.content(this)
}

/**
 * Reusable controls that a [MaplibreMap][org.maplibre.compose.map.MaplibreMap] draws over itself.
 */
@Immutable
public class MapOverlay(
  internal val content: @Composable @UiComposable MapOverlayScope.() -> Unit
) {
  public companion object {
    /** Gap between aligned overlay controls and the edge of the unobstructed map region. */
    public val Spacing: Dp = 8.dp

    /** No controls. Use this when the app shows the attribution somewhere else. */
    public val None: MapOverlay = MapOverlay {}

    /**
     * The MapLibre logo and an attribution button along the bottom edge.
     *
     * Most maps serve tiles under a license that requires attribution, so a map keeps these unless
     * the app shows the attribution somewhere else.
     */
    public val AttributionOnly: MapOverlay = MapOverlay {
      Controls {
        MaplibreLogo(Modifier.align(Alignment.BottomStart))
        ExpandingAttributionButton(Modifier.align(Alignment.BottomEnd))
      }
    }

    /**
     * A scale bar and a compass along the top edge, and the controls from [AttributionOnly] along
     * the bottom edge. The scale bar and the compass appear only while they are relevant.
     *
     * A [MaplibreMap][org.maplibre.compose.map.MaplibreMap] draws these unless the caller replaces
     * them.
     */
    public val Default: MapOverlay = MapOverlay {
      Controls {
        DisappearingScaleBar(
          metersPerDp = mapState.viewport?.metersPerDpAtTarget ?: 0.0,
          zoom = mapState.cameraPosition.zoom,
          modifier = Modifier.align(Alignment.TopStart),
        )

        DisappearingCompassButton(modifier = Modifier.align(Alignment.TopEnd))
      }
      include(AttributionOnly)
    }

    /**
     * The controls from [Default], plus zoom buttons at the middle of the end edge, clear of the
     * compass above and the attribution below.
     *
     * The zoom buttons complement the zoom gestures; they serve pointer devices and accessibility.
     */
    public val Full: MapOverlay = MapOverlay {
      include(Default)
      Controls { ZoomButtons(Modifier.align(Alignment.CenterEnd)) }
    }
  }
}

@Composable
internal fun MapOverlayHost(
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit,
  mapState: MapState,
  cameraPadding: PaddingValues = PaddingValues(0.dp),
  modifier: Modifier = Modifier,
) {
  val coordinates = remember { OverlayCoordinates() }
  Box(modifier.onPlaced { coordinates.value = it }) {
    val scope =
      remember(mapState, cameraPadding, this) {
        MapOverlayScopeImpl(mapState, cameraPadding, this, coordinates)
      }
    overlay(scope)
  }
}

internal class OverlayCoordinates {
  var value: LayoutCoordinates? by mutableStateOf(null)
}

@Stable
internal class MapOverlayScopeImpl(
  override val mapState: MapState,
  override val cameraPadding: PaddingValues,
  boxScope: BoxScope,
  val coordinates: OverlayCoordinates,
) : MapOverlayScope, BoxScope by boxScope
