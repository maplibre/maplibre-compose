package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.LayerPropertyCompiler
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.ClickRoute
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleCompositionHost
import org.maplibre.compose.style.StyleError
import org.maplibre.compose.style.StyleHostDispatcher
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.styleHostDispatcher
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * The map's identity, held apart from the composable that shows it: the selected [baseStyle], the
 * style content that [setStyleContent] composes over it, and the [camera].
 *
 * A state outlives any one [MaplibreMap] composable. On Android, iOS, and Desktop the loaded map
 * survives the composable leaving the composition, with its style and camera intact, and
 * re-attaches to the next [MaplibreMap] that receives this state. On Web the live map exists only
 * while a [MaplibreMap] is composed, and the state replays the selected style into the next one.
 *
 * Construct a state directly to own the map outside the composition, for example in a ViewModel,
 * and pass it to [MaplibreMap]. Inside a composition, [rememberMapState] constructs one and closes
 * it when the composition leaves. The owner that constructed a state calls [close]; a closed state
 * cannot show a map again.
 */
// Internally this also owns the style composition host, the root StyleNode over the loaded
// StyleBinding, and the wiring into CameraState and StyleSources. A session (MapAdapter) attaches
// through attachSession and detaches through detachSession; the style the session loads arrives
// through callbacks and re-points the persistent node, which reapplies the whole desired state to
// the new style. The content follows the style the application has *selected* while the node
// targets the *loaded* style; during a switch those differ and nothing here reconciles them. The
// binding dropping writes after unload is what makes that survivable.
public class MapState
internal constructor(
  internal val cameraState: CameraState,
  density: Density = Density(1f),
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  logger: Logger? = null,
  inheritedLocals: CompositionLocalContext? = null,
  hostDispatcher: StyleHostDispatcher = styleHostDispatcher(),
) : AutoCloseable {

  /**
   * Creates a state that owns its own camera and style wiring.
   *
   * A detached state rasterizes painters in the style content at [density] and [layoutDirection]; a
   * [MaplibreMap] that later receives this state replaces both with the composition's values.
   */
  public constructor(
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  ) : this(
    cameraState = CameraState(CameraPosition()),
    density = density,
    layoutDirection = layoutDirection,
  )

  internal val styleNode: StyleNode = StyleNode(StyleBinding.UNLOADED, logger)

  /** Owns the map's platform lifetime; on some platforms the map outlives the composition. */
  internal val engine: MapEngine = createMapEngine(this)

  internal val host: StyleCompositionHost =
    StyleCompositionHost(
      rootNode = styleNode,
      dispatcher = hostDispatcher.dispatcher,
      density = density,
      layoutDirection = layoutDirection,
      logger = logger,
      mapState = this,
      onClosed = hostDispatcher::close,
    )

  internal var logger: Logger? = logger
    set(value) {
      field = value
      styleNode.logger = value
      host.logger = value
    }

  internal var density: Density
    get() = host.density
    set(value) {
      host.density = value
      cameraState.density = value
    }

  internal var layoutDirection: LayoutDirection
    get() = host.layoutDirection
    set(value) {
      host.layoutDirection = value
    }

  internal var inheritedLocals: CompositionLocalContext?
    get() = host.inheritedLocals
    set(value) {
      host.inheritedLocals = value
    }

  // A UI SideEffect writes these hooks and the map's owner and renderer threads read them, so
  // Volatile supplies the only happens-before edge between the write and those reads.
  @Volatile internal var onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass }
  @Volatile internal var onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass }
  @Volatile internal var onFrame: (framesPerSecond: Double) -> Unit = {}
  @Volatile internal var onMapLoadFailed: (reason: String?) -> Unit = {}
  @Volatile internal var onMapLoadFinished: () -> Unit = {}

  /** The scope click queries launch on; null drops clicks, which only a missing UI would cause. */
  @Volatile internal var clickScope: CoroutineScope? = null

  private val contentState = mutableStateOf<(@Composable @MaplibreComposable () -> Unit)>({})

  init {
    cameraState.density = density
    host.inheritedLocals = inheritedLocals
  }

  private var contentStarted = false

  /**
   * Replaces the style content of this map with [content].
   *
   * The content composes into the map's style the way `setContent` composes a window's UI tree: one
   * composition per map, and a second call replaces the whole content. Snapshot state that the
   * content reads recomposes it. The composition runs on a dispatcher this state owns, so effects
   * inside the content run outside the UI context, and source and anchor validation surface when
   * the content applies to a loaded style rather than at the call.
   *
   * Inside the content, [LocalMapState] returns this state, so the content can read the camera and
   * the viewport of the map it composes into.
   *
   * Call this on a state that lives outside the composition, such as one a ViewModel owns. Inside a
   * composition, pass the content to [rememberMapState] instead.
   */
  public fun setStyleContent(content: @Composable @MaplibreComposable () -> Unit) {
    updateStyleContent(content)
    startStyleComposition()
  }

  /** Replaces the style content; the host recomposes because it reads this state. */
  internal fun updateStyleContent(content: @Composable @MaplibreComposable () -> Unit) {
    // A write with no read, so a UI composition calling this never subscribes to the state.
    contentState.value = content
  }

  /**
   * Starts the style composition. Called after the snapshot that constructed this state has
   * applied: content the host composes before then reads this state's records too early to be
   * invalidated by their first commit.
   */
  internal fun startStyleComposition() {
    if (contentStarted) return
    contentStarted = true
    host.setContent { contentState.value.invoke() }
  }

  /**
   * Failures in the style content composition or in applying its changes to the loaded style. The
   * map and this state survive each failure and keep working; fatal errors propagate instead of
   * arriving here.
   *
   * An error emitted while nothing collects the flow is dropped, and a slow collector loses errors
   * beyond a small buffer. The log records every failure.
   */
  public val styleErrors: SharedFlow<StyleError>
    get() = host.styleErrors

  /**
   * The loaded style's layers, in draw order. See [StyleLayers] for the map-owned versus
   * composition-owned split. The collection is empty while no style is loaded, and repopulates when
   * a session loads [baseStyle].
   */
  public val layers: StyleLayers = StyleLayers(this)

  /**
   * The loaded style's sources. See [StyleSources] for the composition-owned read path. The
   * collection is empty while no style is loaded, and repopulates when a session loads [baseStyle].
   */
  public val sources: StyleSources = StyleSources(this)

  /** Backs [StyleLayers.ids]; refreshed by [refreshStyleCollections]. */
  internal val layerIdsState = mutableStateOf(emptyList<String>())

  private fun refreshStyleCollections() {
    layerIdsState.value = styleNode.binding.layerIds().orEmpty()
    sources.refreshSources()
  }

  /** Refuses an imperative write on a layer id that the style content owns. */
  internal fun checkLayerWritable(id: String) {
    check(id !in styleNode.compositionLayerIds) {
      "Layer '$id' is owned by the style content composition; change it by recomposing the " +
        "content rather than through MapState.layers"
    }
  }

  /** Compiles [expression] with this state's density and layout direction, as the content does. */
  internal fun compileLayerProperty(expression: Expression<*>): JsonElement =
    LayerPropertyCompiler(styleNode, host.density, host.layoutDirection)
      .compile(expression)
      .toStyleJson()

  /** The attached session's adapter, so a base-style change reaches the live map. */
  private var adapter: MapAdapter? = null

  /** The style the application selected; null until the first [baseStyle] assignment. */
  private var selectedBaseStyle: BaseStyle? = null

  /**
   * The URI or JSON of the style the map loads underneath the composed content, initially
   * [BaseStyle.Demo]. See [MapLibre Style](https://maplibre.org/maplibre-style-spec/). Assigning a
   * new value reloads the style on the live map; the map re-adds the composed content over it.
   */
  public var baseStyle: BaseStyle
    get() = selectedBaseStyle ?: BaseStyle.Demo
    set(value) {
      if (value == selectedBaseStyle) return
      selectedBaseStyle = value
      engine.setBaseStyle(value)
      adapter?.setBaseStyle(value)
    }

  /** Selects [BaseStyle.Demo] on a state that never selected a style, so a session has one. */
  internal fun ensureBaseStyleSelected() {
    if (selectedBaseStyle == null) baseStyle = BaseStyle.Demo
  }

  /**
   * The camera position of the map. A composition that reads this property recomposes after each
   * camera move.
   *
   * While no session is attached, this property reports the position that the state last recorded.
   * On Android, iOS, and Desktop the loaded map keeps its camera across detach, and the recorded
   * position matches it. On Web the next session starts the map at the recorded position.
   *
   * [setCamera] and [animateCamera] write the camera.
   */
  public val camera: CameraPosition
    get() = cameraState.position

  /**
   * What the map shows right now: the size of the map composable and the visible area. Null while
   * no attached session has rendered a viewport. A composition that reads this property recomposes
   * after a camera move or a resize of the map composable, because the instance is replaced once
   * the map has adopted either change.
   */
  public val viewport: Viewport?
    get() = cameraState.viewport

  /** Whether the camera is currently moving. */
  public val isCameraMoving: Boolean
    get() = cameraState.isCameraMoving

  /** The reason for the most recent camera move. */
  public val cameraMoveReason: CameraMoveReason
    get() = cameraState.moveReason

  /**
   * Moves the camera to [position] with no animation.
   *
   * A call before a session attaches records the position, and the map starts there when a session
   * attaches.
   */
  public suspend fun setCamera(position: CameraPosition) {
    cameraState.position = position
  }

  /**
   * Moves the camera to fit [boundingBox] with no animation.
   *
   * A call before a session attaches suspends until a session attaches and applies the move.
   *
   * @param boundingBox The bounds that the move fits into the viewport.
   * @param bearing The bearing that the move sets. Defaults to 0.0.
   * @param tilt The tilt that the move sets. Defaults to 0.0.
   * @param padding Insets added while fitting [boundingBox].
   */
  public suspend fun setCamera(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
  ) {
    cameraState.jumpTo(boundingBox, bearing, tilt, padding)
  }

  /**
   * Animates the camera to [position] over [duration] and returns when the animation ends.
   *
   * A call before a session attaches suspends until a session attaches and runs the animation.
   */
  public suspend fun animateCamera(
    position: CameraPosition,
    duration: Duration = 300.milliseconds,
  ) {
    cameraState.animateTo(position, duration)
  }

  /**
   * Animates the camera to fit [boundingBox] over [duration] and returns when the animation ends.
   *
   * A call before a session attaches suspends until a session attaches and runs the animation.
   *
   * @param boundingBox The bounds that the animation fits into the viewport.
   * @param bearing The bearing that the animation sets. Defaults to 0.0.
   * @param tilt The tilt that the animation sets. Defaults to 0.0.
   * @param padding Insets added while fitting [boundingBox].
   * @param duration The duration of the animation. Defaults to 300 ms.
   */
  public suspend fun animateCamera(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
    duration: Duration = 300.milliseconds,
  ) {
    cameraState.animateTo(boundingBox, bearing, tilt, padding, duration)
  }

  /**
   * Returns the offset from the top-left corner of the map composable that corresponds to the given
   * [position], including a [position] outside the viewport. Returns null while no attached session
   * has rendered a viewport.
   *
   * The answer describes the transform that the map has at the time of the call.
   */
  public fun screenLocationFromPosition(position: Position): DpOffset? =
    cameraState.screenLocationFromPosition(position)

  /**
   * Returns the position that corresponds to the given [offset] from the top-left corner of the map
   * composable. Returns null while no attached session has rendered a viewport.
   *
   * The answer describes the transform that the map has at the time of the call.
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position? =
    cameraState.positionFromScreenLocation(offset)

  /**
   * Returns the position that corresponds to the given [offset] in pixels from the top-left corner
   * of the map composable, in the units that pointer events report. Returns null while no attached
   * session has rendered a viewport.
   *
   * The answer describes the transform that the map has at the time of the call.
   */
  public fun positionFromScreenLocation(offset: Offset): Position? =
    cameraState.positionFromScreenLocation(offset)

  /**
   * Returns the features that are rendered at the given [offset] from the top-left corner of the
   * map composable, optionally limited to layers with the given [layerIds] and filtered by the
   * given [predicate]. The result is sorted by render order, so the feature in front is first in
   * the list. The list is empty while no session is attached.
   *
   * @param offset The offset from the top-left corner of the map composable to query.
   * @param layerIds The ids of the layers that limit the query. If not specified, the query returns
   *   features in *any* layer.
   * @param predicate An expression that has to evaluate to true for a feature to be included in the
   *   result.
   */
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    cameraState.queryRenderedFeatures(offset, layerIds, predicate)

  /**
   * Returns the features whose rendered geometry intersects the given [rect], optionally limited to
   * layers with the given [layerIds] and filtered by the given [predicate]. The result is sorted by
   * render order, so the feature in front is first in the list. The list is empty while no session
   * is attached.
   *
   * @param rect The rectangle to intersect with rendered geometry.
   * @param layerIds The ids of the layers that limit the query. If not specified, the query returns
   *   features in *any* layer.
   * @param predicate An expression that has to evaluate to true for a feature to be included in the
   *   result.
   */
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    cameraState.queryRenderedFeatures(rect, layerIds, predicate)

  /** Wires [adapter] into the camera; the style arrives later through [callbacks]. */
  internal fun attachSession(adapter: MapAdapter) {
    this.adapter = adapter
    selectedBaseStyle?.let(adapter::setBaseStyle)
    cameraState.map = adapter
    sources.refreshSources()
  }

  /** Unwires the session; the state, its content, and its desired style survive for the next. */
  internal fun detachSession() {
    adapter = null
    cameraState.map = null
    // An engine that keeps the map alive keeps its loaded binding and the applied snapshot too.
    if (!engine.retainsStyleAcrossDetach) updateBinding(null)
    sources.clear()
  }

  /** Applies the composable's per-composition options; attach-independent, safe to repeat. */
  internal fun applyOptions(
    map: MapAdapter,
    cameraPadding: PaddingValues,
    zoomRange: ClosedRange<Float>,
    pitchRange: ClosedRange<Float>,
    boundingBox: BoundingBox?,
    options: MapOptions,
  ) {
    map.setCameraPadding(cameraPadding)
    map.setMinZoom(zoomRange.start.toDouble())
    map.setMaxZoom(zoomRange.endInclusive.toDouble())
    map.setMinPitch(pitchRange.start.toDouble())
    map.setMaxPitch(pitchRange.endInclusive.toDouble())
    map.setRenderSettings(options.renderOptions)
    map.setGestureSettings(options.gestureOptions)
    map.setTileLodSettings(options.tileLodOptions)
    map.setCameraBoundingBox(boundingBox)
  }

  private fun updateBinding(newBinding: StyleBinding?) {
    styleNode.binding = newBinding ?: StyleBinding.UNLOADED
    refreshStyleCollections()
    host.requestApplyChanges()
  }

  /**
   * Releases the map and the style composition. The owner that constructed the state calls this;
   * [rememberMapState] closes the states it created when the composition leaves.
   */
  override fun close() {
    detachSession()
    engine.close()
    host.close()
  }

  internal val callbacks: MapAdapter.Callbacks =
    object : MapAdapter.Callbacks {
      override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
        updateBinding(style)
        if (cameraState.map === map) cameraState.viewportState.value = map.getViewport()
      }

      override fun onMapFailLoading(reason: String?) {
        onMapLoadFailed(reason)
      }

      override fun onMapFinishedLoading(map: MapAdapter) {
        refreshStyleCollections()
        onMapLoadFinished()
      }

      override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
        if (sourceId == null) sources.refreshSources() else sources.refreshSource(sourceId)
        layerIdsState.value = styleNode.binding.layerIds().orEmpty()
      }

      override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
        if (cameraState.map !== map) return
        cameraState.moveReasonState.value = reason
        cameraState.isCameraMovingState.value = true
      }

      override fun onCameraMoved(map: MapAdapter) {
        if (cameraState.map !== map) return
        cameraState.positionState.value = map.getCameraPosition()
        // A new instance so a composition that reads MapState.viewport redraws when the
        // transform changes without the camera position changing, which is what a resize does.
        cameraState.viewportState.value = map.getViewport()
      }

      override fun onCameraMoveEnded(map: MapAdapter) {
        if (cameraState.map !== map) return
        cameraState.isCameraMovingState.value = false
      }

      /** Offers the click to each layer that has a [handlerOf] handler, topmost first. */
      private fun routeClick(
        map: MapAdapter,
        offset: DpOffset,
        handlerOf: (ClickRoute) -> FeaturesClickHandler?,
      ) {
        // The host publishes the routing snapshot after each sync; reading only the snapshot
        // keeps this off the mutable node tree the host owns.
        clickScope?.launch {
          for (route in styleNode.clickRoutes) {
            if (handlerOf(route) == null) continue
            val features =
              map.queryRenderedFeatures(
                offset = offset,
                layerIds = setOf(route.layerId),
                predicate = null,
              )
            // Recomposition may replace or remove the layer while the query is suspended. A
            // removed layer never receives the click; a replaced one answers with the handler
            // the latest snapshot has.
            val currentHandle =
              styleNode.clickRoutes.firstOrNull { it.layerId == route.layerId }?.let(handlerOf)
                ?: continue
            if (features.isNotEmpty() && currentHandle(features).consumed) break
          }
        }
      }

      override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
        if (onMapClick(latLng, offset).consumed) return
        routeClick(map, offset) { it.onClick }
      }

      override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
        if (onMapLongClick(latLng, offset).consumed) return
        routeClick(map, offset) { it.onLongClick }
      }

      override fun onFrame(fps: Double) {
        this@MapState.onFrame(fps)
      }
    }
}
