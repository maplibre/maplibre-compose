package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.LocalMapState
import org.maplibre.compose.map.LocalViewport
import org.maplibre.compose.map.MapState

/**
 * Layout-owned viewport insets of the enclosing map presentation, available to its overlay content
 * without waiting for a rendered frame. Excludes app-controlled camera padding. Zero outside an
 * overlay.
 */
public val LocalViewportInsets: ProvidableCompositionLocal<PaddingValues> = compositionLocalOf {
  PaddingValues(0.dp)
}

internal val LocalMapCoordinates =
  staticCompositionLocalOf<State<LayoutCoordinates?>> {
    error("Geographic placement requires a MaplibreMap overlay")
  }

/** Default controls use viewport insets or remaining safe-area insets, plus a small margin. */
@Composable
internal fun DefaultControls(
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = LocalViewportInsets.current,
  contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier
      .fillMaxSize()
      .padding(contentPadding)
      .consumeWindowInsets(contentPadding)
      .windowInsetsPadding(contentWindowInsets)
      .padding(MapOverlay.Spacing),
    content = content,
  )
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
      DefaultControls {
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
      val mapState = checkNotNull(LocalMapState.current)
      DefaultControls {
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
      DefaultControls { ZoomButtons(Modifier.align(Alignment.CenterEnd)) }
    }
  }
}

@Composable
internal fun MapOverlayHost(
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit,
  mapState: MapState,
  viewportInsets: PaddingValues = PaddingValues(0.dp),
  modifier: Modifier = Modifier,
) {
  val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
  CompositionLocalProvider(
    LocalMapState provides mapState,
    LocalViewport provides mapState.viewport,
    LocalViewportInsets provides viewportInsets,
    LocalMapCoordinates provides coordinates,
  ) {
    GeographicLayout(modifier.onPlaced { coordinates.value = it }, content = overlay)
  }
}
