package dev.sargunv.maplibrecompose.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import co.touchlab.kermit.Logger
import dev.sargunv.maplibrecompose.compose.engine.LayerNode
import dev.sargunv.maplibrecompose.compose.engine.rememberStyleComposition
import dev.sargunv.maplibrecompose.core.CameraMoveReason
import dev.sargunv.maplibrecompose.core.GestureSettings
import dev.sargunv.maplibrecompose.core.MapOptions
import dev.sargunv.maplibrecompose.core.MaplibreMap
import dev.sargunv.maplibrecompose.core.OrnamentSettings
import dev.sargunv.maplibrecompose.core.SafeStyle
import dev.sargunv.maplibrecompose.core.StandardMaplibreMap
import dev.sargunv.maplibrecompose.core.Style
import dev.sargunv.maplibrecompose.core.defaultMapOptions
import dev.sargunv.maplibrecompose.core.util.PlatformUtils
import io.github.dellisd.spatialk.geojson.Position
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Displays a MapLibre based map.
 *
 * @param modifier The modifier to be applied to the layout.
 * @param styleUri The URI of the map style specification JSON to use, see
 *   [MapLibre Style](https://maplibre.org/maplibre-style-spec/).
 * @param zoomRange The allowable bounds for the camera zoom level.
 * @param pitchRange The allowable bounds for the camera pitch.
 * @param gestureSettings Defines which user map gestures are enabled.
 * @param ornamentSettings Defines which additional UI elements are displayed on top of the map.
 * @param cameraState The camera state specifies what position of the map is rendered, at what zoom,
 *   at what tilt, etc.
 * @param onMapClick Invoked when the map is clicked. A click callback can be defined per layer,
 *   too, see e.g. the `onClick` parameter for
 *   [LineLayer][dev.sargunv.maplibrecompose.compose.layer.LineLayer]. However, this callback is
 *   always called first and can thus prevent subsequent callbacks to be invoked by consuming the
 *   event.
 * @param onMapLongClick Invoked when the map is long-clicked. See [onMapClick].
 * @param onFrame Invoked on every rendered frame.
 * @param isDebugEnabled Whether the map debug information is shown.
 * @param maximumFps The maximum frame rate at which the map view is rendered, but it can't exceed
 *   the ability of device hardware.
 *
 * Note: This parameter does not take effect on web and desktop.
 *
 * @param logger kermit logger to use
 * @param content The map content additional to what is already part of the map as defined in the
 *   base map style linked in [styleUri].
 *
 * Additional [sources](https://maplibre.org/maplibre-style-spec/sources/) can be added via:
 * - [rememberGeoJsonSource][dev.sargunv.maplibrecompose.compose.source.rememberGeoJsonSource] (see
 *   [GeoJsonSource][dev.sargunv.maplibrecompose.core.source.GeoJsonSource]),
 * - [rememberVectorSource][dev.sargunv.maplibrecompose.compose.source.rememberVectorSource] (see
 *   [VectorSource][dev.sargunv.maplibrecompose.core.source.VectorSource]),
 * - [rememberRasterSource][dev.sargunv.maplibrecompose.compose.source.rememberRasterSource] (see
 *   [RasterSource][dev.sargunv.maplibrecompose.core.source.RasterSource])
 *
 * A source that is already defined in the base map style can be referenced via
 * [getBaseSource][dev.sargunv.maplibrecompose.compose.source.getBaseSource].
 *
 * The data from a source can then be used in
 * [layer](https://maplibre.org/maplibre-style-spec/layers/) definition(s), which define how that
 * data is rendered, see:
 * - [BackgroundLayer][dev.sargunv.maplibrecompose.compose.layer.BackgroundLayer]
 * - [LineLayer][dev.sargunv.maplibrecompose.compose.layer.LineLayer]
 * - [FillExtrusionLayer][dev.sargunv.maplibrecompose.compose.layer.FillExtrusionLayer]
 * - [FillLayer][dev.sargunv.maplibrecompose.compose.layer.FillLayer]
 * - [HeatmapLayer][dev.sargunv.maplibrecompose.compose.layer.HeatmapLayer]
 * - [HillshadeLayer][dev.sargunv.maplibrecompose.compose.layer.HillshadeLayer]
 * - [LineLayer][dev.sargunv.maplibrecompose.compose.layer.LineLayer]
 * - [RasterLayer][dev.sargunv.maplibrecompose.compose.layer.RasterLayer]
 * - [SymbolLayer][dev.sargunv.maplibrecompose.compose.layer.SymbolLayer]
 *
 * By default, the layers defined in this scope are put on top of the layers from the base style, in
 * the order they are defined. Alternatively, it is possible to anchor layers at certain layers from
 * the base style. This is done, for example, in order to add a layer just below the first symbol
 * layer from the base style so that it isn't above labels. See:
 * - [Anchor.Top][dev.sargunv.maplibrecompose.compose.layer.Anchor.Companion.Top],
 * - [Anchor.Bottom][dev.sargunv.maplibrecompose.compose.layer.Anchor.Companion.Bottom],
 * - [Anchor.Above][dev.sargunv.maplibrecompose.compose.layer.Anchor.Companion.Above],
 * - [Anchor.Below][dev.sargunv.maplibrecompose.compose.layer.Anchor.Companion.Below],
 * - [Anchor.Replace][dev.sargunv.maplibrecompose.compose.layer.Anchor.Companion.Replace],
 * - [Anchor.At][dev.sargunv.maplibrecompose.compose.layer.Anchor.Companion.At]
 */
@Composable
public fun MaplibreMap(
  modifier: Modifier = Modifier,
  styleUri: String = "https://demotiles.maplibre.org/style.json",
  zoomRange: ClosedRange<Float> = 0f..20f,
  pitchRange: ClosedRange<Float> = 0f..60f,
  gestureSettings: GestureSettings = GestureSettings.AllEnabled,
  ornamentSettings: OrnamentSettings = OrnamentSettings.AllEnabled,
  cameraState: CameraState = rememberCameraState(),
  styleState: StyleState = rememberStyleState(),
  onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onFrame: (framesPerSecond: Double) -> Unit = {},
  isDebugEnabled: Boolean = false,
  maximumFps: Int = PlatformUtils.getSystemRefreshRate().roundToInt(),
  logger: Logger? = remember { Logger.withTag("maplibre-compose") },
  platformOptions: MapOptions = defaultMapOptions(),
  content: @Composable @MaplibreComposable () -> Unit = {},
) {
  var rememberedStyle by remember { mutableStateOf<SafeStyle?>(null) }
  val styleComposition by rememberStyleComposition(styleState, rememberedStyle, logger, content)

  val callbacks =
    remember(cameraState, styleState, styleComposition) {
      object : MaplibreMap.Callbacks {
        override fun onStyleChanged(map: MaplibreMap, style: Style?) {
          map as StandardMaplibreMap
          rememberedStyle?.unload()
          val safeStyle = style?.let { SafeStyle(it) }
          rememberedStyle = safeStyle
          cameraState.metersPerDpAtTargetState.value =
            map.metersPerDpAtLatitude(map.getCameraPosition().target.latitude)
        }

        override fun onMapFinishedLoading(map: MaplibreMap) {
          map as StandardMaplibreMap
          styleState.reloadSources()
        }

        override fun onCameraMoveStarted(map: MaplibreMap, reason: CameraMoveReason) {
          cameraState.moveReasonState.value = reason
          cameraState.isCameraMovingState.value = true
        }

        override fun onCameraMoved(map: MaplibreMap) {
          map as StandardMaplibreMap
          cameraState.positionState.value = map.getCameraPosition()
          cameraState.metersPerDpAtTargetState.value =
            map.metersPerDpAtLatitude(map.getCameraPosition().target.latitude)
        }

        override fun onCameraMoveEnded(map: MaplibreMap) {
          cameraState.isCameraMovingState.value = false
        }

        private fun layerNodesInOrder(): List<LayerNode<*>> {
          val layerNodes =
            (styleComposition?.children?.filterIsInstance<LayerNode<*>>() ?: emptyList())
              .associateBy { node -> node.layer.id }
          val layers = styleComposition?.style?.getLayers() ?: emptyList()
          return layers.asReversed().mapNotNull { layer -> layerNodes[layer.id] }
        }

        override fun onClick(map: MaplibreMap, latLng: Position, offset: DpOffset) {
          map as StandardMaplibreMap
          if (onMapClick(latLng, offset).consumed) return
          layerNodesInOrder().find { node ->
            val handle = node.onClick ?: return@find false
            val features =
              map.queryRenderedFeatures(
                offset = offset,
                layerIds = setOf(node.layer.id),
                predicate = null,
              )
            features.isNotEmpty() && handle(features).consumed
          }
        }

        override fun onLongClick(map: MaplibreMap, latLng: Position, offset: DpOffset) {
          map as StandardMaplibreMap
          if (onMapLongClick(latLng, offset).consumed) return
          layerNodesInOrder().find { node ->
            val handle = node.onLongClick ?: return@find false
            val features =
              map.queryRenderedFeatures(
                offset = offset,
                layerIds = setOf(node.layer.id),
                predicate = null,
              )
            features.isNotEmpty() && handle(features).consumed
          }
        }

        override fun onFrame(fps: Double) {
          onFrame(fps)
        }
      }
    }

  val scope = rememberCoroutineScope()

  ComposableMapView(
    modifier = modifier.fillMaxSize(),
    styleUri = styleUri,
    update = { map ->
      when (map) {
        is StandardMaplibreMap -> {
          cameraState.map = map
          map.setDebugEnabled(isDebugEnabled)
          map.setMinZoom(zoomRange.start.toDouble())
          map.setMaxZoom(zoomRange.endInclusive.toDouble())
          map.setMinPitch(pitchRange.start.toDouble())
          map.setMaxPitch(pitchRange.endInclusive.toDouble())
          map.setGestureSettings(gestureSettings)
          map.setOrnamentSettings(ornamentSettings)
          map.setMaximumFps(maximumFps)
        }

        else ->
          scope.launch {
            map.asyncSetDebugEnabled(isDebugEnabled)
            map.asyncSetMinZoom(zoomRange.start.toDouble())
            map.asyncSetMaxZoom(zoomRange.endInclusive.toDouble())
            map.asyncSetMinPitch(pitchRange.start.toDouble())
            map.asyncSetMaxPitch(pitchRange.endInclusive.toDouble())
            map.asyncSetGestureSettings(gestureSettings)
            map.asyncSetOrnamentSettings(ornamentSettings)
            map.asyngSetMaximumFps(maximumFps)
          }
      }
    },
    onReset = {
      cameraState.map = null
      rememberedStyle = null
    },
    logger = logger,
    callbacks = callbacks,
    rememberedStyle = rememberedStyle,
    platformOptions = platformOptions,
  )
}
