package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.ClickRoute
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleCompositionHost
import org.maplibre.compose.style.StyleHostDispatcher
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.styleHostDispatcher
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * The map's identity, held apart from the composable that shows it: the selected [baseStyle] and
 * the style content that [setStyleContent] composes over it.
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
// StyleBinding, and the wiring into CameraState and StyleState. A session (MapAdapter) attaches
// through attachSession and detaches through detachSession; the style the session loads arrives
// through callbacks and re-points the persistent node, which reapplies the whole desired state to
// the new style. The content follows the style the application has *selected* while the node
// targets the *loaded* style; during a switch those differ and nothing here reconciles them. The
// binding dropping writes after unload is what makes that survivable.
public class MapState
internal constructor(
  cameraState: CameraState,
  styleState: StyleState,
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
    styleState = StyleState(),
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
      onClosed = hostDispatcher::close,
    )

  // Snapshot-backed so a composition that reads the wired state recomposes when a legacy
  // MaplibreMap call swaps a new CameraState or StyleState in.
  private val cameraStateHolder = mutableStateOf(cameraState)

  internal var cameraState: CameraState
    get() = cameraStateHolder.value
    set(value) {
      val current = cameraStateHolder.value
      if (current === value) return
      val adapter = current.map
      current.map = null
      cameraStateHolder.value = value
      value.density = host.density
      value.map = adapter
    }

  private val styleStateHolder = mutableStateOf(styleState)

  internal var styleState: StyleState
    get() = styleStateHolder.value
    set(value) {
      val current = styleStateHolder.value
      if (current === value) return
      current.detach()
      styleStateHolder.value = value
      value.attach(styleNode)
    }

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
    this.cameraState.density = density
    this.host.inheritedLocals = inheritedLocals
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

  /** Wires [adapter] into the camera; the style arrives later through [callbacks]. */
  internal fun attachSession(adapter: MapAdapter) {
    this.adapter = adapter
    selectedBaseStyle?.let(adapter::setBaseStyle)
    cameraState.map = adapter
    styleState.attach(styleNode)
  }

  /** Unwires the session; the state, its content, and its desired style survive for the next. */
  internal fun detachSession() {
    adapter = null
    cameraState.map = null
    // An engine that keeps the map alive keeps its loaded binding and the applied snapshot too.
    if (!engine.retainsStyleAcrossDetach) updateBinding(null)
    styleState.detach()
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
    styleState.refreshSources()
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
        styleState.refreshSources()
        onMapLoadFinished()
      }

      override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
        if (sourceId == null) styleState.refreshSources() else styleState.refreshSource(sourceId)
      }

      override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
        if (cameraState.map !== map) return
        cameraState.moveReasonState.value = reason
        cameraState.isCameraMovingState.value = true
      }

      override fun onCameraMoved(map: MapAdapter) {
        if (cameraState.map !== map) return
        cameraState.positionState.value = map.getCameraPosition()
        // A new instance so a composition that reads CameraState.viewport redraws when the
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
