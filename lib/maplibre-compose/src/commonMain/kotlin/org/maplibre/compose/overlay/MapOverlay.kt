package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.style.StyleState

/**
 * Receiver for the controls drawn on top of a [MaplibreMap][org.maplibre.compose.map.MaplibreMap].
 *
 * The scope lays its children out in a box that covers the unobstructed region of the map, so
 * `Modifier.align` positions a control against an edge that nothing else covers. The map state that
 * controls read is available here, which is why the default controls take no arguments.
 */
@LayoutScopeMarker
@Stable
public interface MapOverlayScope : BoxScope {
  /** The camera state of the map that this overlay belongs to. */
  public val cameraState: CameraState

  /** The style state of the map that this overlay belongs to. */
  public val styleState: StyleState

  /**
   * The obstructed region of the map, as passed to
   * [MaplibreMap][org.maplibre.compose.map.MaplibreMap]. The overlay box already applies it.
   */
  public val contentWindowInsets: WindowInsets
}

/**
 * The controls that a [MaplibreMap][org.maplibre.compose.map.MaplibreMap] draws on top of itself.
 *
 * The content belongs to a holder rather than to a parameter of the map, because a composable
 * lambda parameter that has a receiver breaks the composable target inference of the map's own
 * `content` parameter.
 */
@Immutable
public class MapOverlay(
  internal val content: @Composable @UiComposable MapOverlayScope.() -> Unit
) {
  public companion object {
    /** Gap between the overlay controls and the edge of the unobstructed map region. */
    public val Spacing: Dp = 8.dp

    /**
     * The MapLibre logo and an attribution button, side by side along the bottom edge.
     *
     * Most maps serve tiles under a license that requires attribution, so a map draws these unless
     * the caller replaces them.
     */
    public val Default: MapOverlay = MapOverlay {
      // Read before entering the Row, whose scope shadows this one.
      val camera = cameraState
      val style = styleState
      Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        MaplibreLogo()
        ExpandingAttributionButton(cameraState = camera, styleState = style)
      }
    }

    /** Draws the map alone. Use this when you draw the attribution your style requires yourself. */
    public val None: MapOverlay = MapOverlay {}
  }
}

@Stable
internal class MapOverlayScopeImpl(
  boxScope: BoxScope,
  override val cameraState: CameraState,
  override val styleState: StyleState,
  override val contentWindowInsets: WindowInsets,
) : MapOverlayScope, BoxScope by boxScope
