package org.maplibre.compose.style

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
import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Position
import kotlinx.coroutines.launch
import org.maplibre.compose.core.BaseStyle
import org.maplibre.compose.core.CameraMoveReason
import org.maplibre.compose.core.MapOptions
import org.maplibre.compose.core.MaplibreMap
import org.maplibre.compose.core.SafeStyle
import org.maplibre.compose.core.StandardMaplibreMap
import org.maplibre.compose.core.Style
import org.maplibre.compose.style.engine.LayerNode
import org.maplibre.compose.style.engine.rememberStyleComposition

/**
 * Displays a MapLibre based map.
 *
 * @param modifier The modifier to be applied to the layout.
 * @param baseStyle The URI or JSON of the map style to use. See
 *   [MapLibre Style](https://maplibre.org/maplibre-style-spec/).
 * @param cameraState The camera state specifies what position of the map is rendered, at what zoom,
 *   at what tilt, etc.
 * @param zoomRange The allowable camera zoom range.
 * @param pitchRange The allowable camera pitch range.
 * @param boundingBox The allowable bounds for the camera position. On iOS and Web, it prevents the
 *   camera **edges** from going out of bounds. If null is provided, the bounds are reset. On
 *   Android, it prevents the camera **center** from going out of bounds. See
 *   [this GH Issue](https://github.com/maplibre/maplibre-native/issues/3128).
 * @param onMapClick Invoked when the map is clicked. A click callback can be defined per layer,
 *   too, see e.g. the `onClick` parameter for
 *   [LineLayer][org.maplibre.compose.style.layer.LineLayer]. However, this callback is always
 *   called first and can thus prevent subsequent callbacks to be invoked by consuming the event.
 * @param onMapLongClick Invoked when the map is long-clicked. See [onMapClick].
 * @param onFrame Invoked on every rendered frame.
 * @param logger kermit logger to use.
 * @param onMapLoadFailed Invoked when the map failed to load.
 * @param onMapLoadFinished Invoked when the map finished loading.
 * @param content The map content additional to what is already part of the map as defined in the
 *   base map style linked in [baseStyle].
 *
 * Additional [sources](https://maplibre.org/maplibre-style-spec/sources/) can be added via:
 * - [rememberGeoJsonSource][org.maplibre.compose.style.source.rememberGeoJsonSource] (see
 *   [GeoJsonSource][org.maplibre.compose.core.source.GeoJsonSource]),
 * - [rememberVectorSource][org.maplibre.compose.style.source.rememberVectorSource] (see
 *   [VectorSource][org.maplibre.compose.core.source.VectorSource]),
 * - [rememberRasterSource][org.maplibre.compose.style.source.rememberRasterSource] (see
 *   [RasterSource][org.maplibre.compose.core.source.RasterSource])
 *
 * A source that is already defined in the base map style can be referenced via
 * [getBaseSource][org.maplibre.compose.style.source.getBaseSource].
 *
 * The data from a source can then be used in
 * [layer](https://maplibre.org/maplibre-style-spec/layers/) definition(s), which define how that
 * data is rendered, see:
 * - [BackgroundLayer][org.maplibre.compose.style.layer.BackgroundLayer]
 * - [LineLayer][org.maplibre.compose.style.layer.LineLayer]
 * - [FillExtrusionLayer][org.maplibre.compose.style.layer.FillExtrusionLayer]
 * - [FillLayer][org.maplibre.compose.style.layer.FillLayer]
 * - [HeatmapLayer][org.maplibre.compose.style.layer.HeatmapLayer]
 * - [HillshadeLayer][org.maplibre.compose.style.layer.HillshadeLayer]
 * - [LineLayer][org.maplibre.compose.style.layer.LineLayer]
 * - [RasterLayer][org.maplibre.compose.style.layer.RasterLayer]
 * - [SymbolLayer][org.maplibre.compose.style.layer.SymbolLayer]
 *
 * By default, the layers defined in this scope are put on top of the layers from the base style, in
 * the order they are defined. Alternatively, it is possible to anchor layers at certain layers from
 * the base style. This is done, for example, in order to add a layer just below the first symbol
 * layer from the base style so that it isn't above labels. See:
 * - [Anchor.Top][org.maplibre.compose.style.layer.Anchor.Companion.Top],
 * - [Anchor.Bottom][org.maplibre.compose.style.layer.Anchor.Companion.Bottom],
 * - [Anchor.Above][org.maplibre.compose.style.layer.Anchor.Companion.Above],
 * - [Anchor.Below][org.maplibre.compose.style.layer.Anchor.Companion.Below],
 * - [Anchor.Replace][org.maplibre.compose.style.layer.Anchor.Companion.Replace],
 * - [Anchor.At][org.maplibre.compose.style.layer.Anchor.Companion.At]
 */
@Composable
public fun MaplibreMap(
  modifier: Modifier = Modifier,
  baseStyle: BaseStyle = BaseStyle.Demo,
  cameraState: CameraState = rememberCameraState(),
  zoomRange: ClosedRange<Float> = 0f..20f,
  pitchRange: ClosedRange<Float> = 0f..60f,
  boundingBox: BoundingBox? = null,
  styleState: StyleState = rememberStyleState(),
  onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass },
  onFrame: (framesPerSecond: Double) -> Unit = {},
  options: MapOptions = MapOptions(),
  logger: Logger? = remember { Logger.withTag("maplibre-compose") },
  onMapLoadFailed: (reason: String?) -> Unit = {},
  onMapLoadFinished: () -> Unit = {},
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

        override fun onMapFailLoading(reason: String?) {
          onMapLoadFailed(reason)
        }

        override fun onMapFinishedLoading(map: MaplibreMap) {
          map as StandardMaplibreMap
          styleState.reloadSources()
          onMapLoadFinished()
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
    style = baseStyle,
    update = { map ->
      when (map) {
        is StandardMaplibreMap -> {
          cameraState.map = map
          map.setMinZoom(zoomRange.start.toDouble())
          map.setMaxZoom(zoomRange.endInclusive.toDouble())
          map.setMinPitch(pitchRange.start.toDouble())
          map.setMaxPitch(pitchRange.endInclusive.toDouble())
          map.setRenderSettings(options.renderOptions)
          map.setGestureSettings(options.gestureOptions)
          map.setOrnamentSettings(options.ornamentOptions)
          map.setCameraBoundingBox(boundingBox)
        }

        else ->
          scope.launch {
            map.asyncSetMinZoom(zoomRange.start.toDouble())
            map.asyncSetMaxZoom(zoomRange.endInclusive.toDouble())
            map.asyncSetMinPitch(pitchRange.start.toDouble())
            map.asyncSetMaxPitch(pitchRange.endInclusive.toDouble())
            map.asyncSetRenderSettings(options.renderOptions)
            map.asyncSetGestureSettings(options.gestureOptions)
            map.asyncSetOrnamentSettings(options.ornamentOptions)
            map.asyncSetCameraBoundingBox(boundingBox)
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
    options = options,
  )
}
