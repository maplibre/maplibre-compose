package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LayerPropertyCompiler
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleCompositionHost
import org.maplibre.compose.style.StyleError
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** One instance, so repeated clears write the same value and the host does not recompose. */
private val EMPTY_STYLE_COMPOSITION: @Composable @MaplibreComposable () -> Unit = {}

/**
 * The state of one map, held outside the composable that shows it: the selected [baseStyle], the
 * style composition that [setStyleComposition] composes over it, and the [camera].
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
 *
 * A detached state rasterizes painters in the style composition with the constructor's density and
 * layout direction; a [MaplibreMap] that later receives this state replaces both with the
 * composition's values.
 *
 * # Imperative style mutation
 *
 * [layers], [sources], and [images] mutate the loaded style directly, outside the style
 * composition. [StyleSources.add], [StyleSources.remove], [StyleImages.add], and
 * [StyleImages.remove] insert into and remove from the loaded style. [layers] reads and mutates the
 * layers the style already has; the style composition adds and removes layers.
 *
 * A [baseStyle] reload drops every imperative mutation. The reloaded style starts from its own
 * definition, with no imperatively added source or image and no
 * [LayerHandle][org.maplibre.compose.layers.LayerHandle] write. Reapply the mutations when
 * [loadState] becomes [MapLoadState.Ready] for the new generation, or from the [MaplibreMap]
 * `onMapLoadFinished` callback, and watch [styleErrors] for a reapplication the new style refuses.
 */
public class MapState
internal constructor(
  cameraPosition: CameraPosition,
  density: Density = Density(1f),
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  logger: Logger? = null,
  inheritedLocals: CompositionLocalContext? = null,
  hostDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AutoCloseable {

  /**
   * Creates a state that owns its own camera and style wiring.
   *
   * The camera starts at [cameraPosition], and a session that attaches starts the map there.
   *
   * @param density Scales dp-sized values, such as the [captureStillImage] output and rasterized
   *   painter images.
   * @param layoutDirection Resolves direction-aware painters.
   */
  public constructor(
    cameraPosition: CameraPosition = CameraPosition(),
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  ) : this(
    cameraPosition = cameraPosition,
    density = density,
    layoutDirection = layoutDirection,
    logger = null,
  )

  /** The serialized authority for every logical transition this state makes. */
  internal val kernel = MapKernel(cameraPosition)

  internal val adapterState = mutableStateOf<MapAdapter?>(null)
  private val closedState = mutableStateOf(false)
  internal val viewportState = mutableStateOf<Viewport?>(null)
  internal val positionState = mutableStateOf(cameraPosition)
  internal val lastLoadFailure = mutableStateOf<String?>(null)
  internal val moveReasonState = mutableStateOf(CameraMoveReason.NONE)
  internal val isCameraMovingState = mutableStateOf(false)
  private val loadStateHolder = mutableStateOf<MapLoadState>(MapLoadState.Idle)

  /**
   * The load progress of the current [baseStyle] selection. A composition that reads this property
   * recomposes when the current generation starts, finishes, or fails. An event from an earlier
   * generation never changes this value.
   */
  public val loadState: MapLoadState
    get() = loadStateHolder.value

  /** The attached session's adapter, or null while no session is attached. */
  internal val attachedAdapter: MapAdapter?
    get() = adapterState.value

  /** True after [close]; the platform withPlatformMap actuals refuse a closed state with this. */
  internal val isClosed: Boolean
    get() = closedState.value

  /**
   * Whether a [MaplibreMap] shows this state right now. A composition that reads this property
   * recomposes when a map attaches or detaches.
   */
  public val isAttached: Boolean
    get() = adapterState.value != null

  internal val styleNode: StyleNode = StyleNode(StyleBinding.UNLOADED, logger)

  /** Owns the map's platform lifetime; on some platforms the map outlives the composition. */
  internal val engine: MapEngine = MapEngine(this)

  internal val host: StyleCompositionHost =
    StyleCompositionHost(
      rootNode = styleNode,
      uiDispatcher = hostDispatcher,
      density = density,
      layoutDirection = layoutDirection,
      logger = logger,
      mapState = this,
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

  private val contentState = mutableStateOf(EMPTY_STYLE_COMPOSITION)
  private var pendingContent: (@Composable @MaplibreComposable () -> Unit)? = null

  init {
    host.inheritedLocals = inheritedLocals
    styleNode.commitOwnership = { binding, layerIds, sources ->
      commitComposition(binding, layerIds, sources)
    }
  }

  private var contentStarted = false

  /**
   * Replaces the style composition of this map with [content].
   *
   * The content composes into the map's style the way `setContent` composes a window's UI tree: one
   * composition per map, and a second call replaces the whole content. Snapshot state that the
   * content reads recomposes it. Effects inside the content run outside the UI context, and source
   * and anchor validation surface when the content applies to a loaded style rather than at the
   * call.
   *
   * Inside the content, [LocalMapState] returns this state, so the content can read the camera and
   * the viewport of the map it composes into.
   *
   * Call this on a state that lives outside the composition, such as one a ViewModel owns. Inside a
   * composition, pass the content to [rememberMapState] instead.
   */
  public fun setStyleComposition(content: @Composable @MaplibreComposable () -> Unit) {
    updateStyleComposition(content)
    startStyleComposition()
  }

  /** Replaces the style composition; the host recomposes because it reads this state. */
  internal fun updateStyleComposition(content: @Composable @MaplibreComposable () -> Unit) {
    apply {
      if (closed) return@apply
      pendingContent = content
    }
  }

  /**
   * Composes empty content in place of the previous content. Writing the one
   * [EMPTY_STYLE_COMPOSITION] instance again is dropped by snapshot equality, so a state that never
   * had content stays untouched.
   */
  internal fun clearStyleComposition() {
    updateStyleComposition(EMPTY_STYLE_COMPOSITION)
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
   * Failures in the style composition or in applying its changes to the loaded style. The map and
   * this state survive each failure and keep working; fatal errors propagate instead of arriving
   * here.
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
   * The loaded style's sources. See [StyleSources] for the composition-owned read path and for the
   * imperative [add][StyleSources.add] and [remove][StyleSources.remove]. The collection is empty
   * while no style is loaded, and repopulates when a session loads [baseStyle].
   */
  public val sources: StyleSources = StyleSources(this)

  /** The style images that the application registered imperatively. See [StyleImages]. */
  public val images: StyleImages = StyleImages(this)

  internal fun refreshStyleCollections() {
    styleNode.refreshLiveLayerIds()
    sources.refreshSources()
  }

  /**
   * Refuses an imperative write on a layer id that the style composition owns. The read is the
   * kernel's last committed ownership snapshot.
   */
  internal fun checkLayerWritable(id: String) {
    check(!kernel.record.layerIsCompositionOwned(id) && id !in styleNode.compositionLayerIds) {
      "Layer '$id' is owned by the style composition; change it by recomposing the " +
        "content rather than through MapState.layers"
    }
  }

  internal val styleGeneration: Long
    get() = kernel.record.styleGeneration

  /**
   * Runs [write] only when [generation] is still the current style generation. The kernel
   * authorizes the write, the binding mutation runs after that turn, and a superseded generation
   * fails the call instead of publishing a half-applied value.
   */
  internal fun writeAuthorizedLayer(generation: Long, layer: Layer, write: () -> Unit) {
    val (authorized, effects) =
      kernel.reduceValue {
        check(!closed) { "MapState is closed; a closed state cannot mutate the style" }
        check(generation == styleGeneration) {
          "Layer '${layer.id}' was taken from a style that a base style load replaced; get a " +
            "fresh handle from MapState.layers"
        }
        check(!layerIsCompositionOwned(layer.id)) {
          "Layer '${layer.id}' is owned by the style composition; change it by recomposing the " +
            "content rather than through MapState.layers"
        }
        true
      }
    publishRecord()
    executeEffects(effects)
    if (!authorized) return
    write()
    val stillCurrent = kernel.read { !closed && generation == styleGeneration }
    check(stillCurrent) {
      "Layer '${layer.id}' was taken from a style that a base style load replaced; get a fresh " +
        "handle from MapState.layers"
    }
  }

  /** Compiles [expression] with this state's density and layout direction, as the content does. */
  internal fun compileLayerProperty(expression: Expression<*>): JsonElement =
    LayerPropertyCompiler(styleNode, host.density, host.layoutDirection)
      .compileImperative(expression)
      .toStyleJson()

  /**
   * The URI or JSON of the style the map loads underneath the composed content, initially
   * [BaseStyle.Demo]. See [MapLibre Style](https://maplibre.org/maplibre-style-spec/). Assigning a
   * new value reloads the style on the live map; the map re-adds the composed content over it.
   */
  public var baseStyle: BaseStyle
    get() = kernel.record.selectedStyle ?: BaseStyle.Demo
    set(value) {
      apply { selectStyle(value) }
    }

  /** Selects [BaseStyle.Demo] on a state that never selected a style, so a session has one. */
  internal fun ensureBaseStyleSelected() {
    apply { ensureStyleSelected() }
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
    get() = positionState.value

  /**
   * The map's current view: the size of the rendering surface and the visible area. Null while
   * nothing renders this state; an attached session supplies it, and a [captureStillImage] in
   * progress supplies its own. A composition that reads this property recomposes after a camera
   * move or a resize of the map composable.
   */
  public val viewport: Viewport?
    get() = viewportState.value

  /** Whether the camera is currently moving. */
  public val isCameraMoving: Boolean
    get() = isCameraMovingState.value

  /** The reason for the most recent camera move. */
  public val cameraMoveReason: CameraMoveReason
    get() = moveReasonState.value

  /** Suspends until a session attaches, for the camera calls that need a live map. */
  private suspend fun awaitAdapter(): MapAdapter {
    val (adapter, closed) =
      snapshotFlow { attachedAdapter to closedState.value }
        .first { (adapter, closed) -> adapter != null || closed }
    check(!closed) { "MapState is closed; no MaplibreMap can attach to run this camera call" }
    return checkNotNull(adapter)
  }

  /**
   * Moves the camera to [position] with no animation.
   *
   * A call before a session attaches records the position, and the map starts there when a session
   * attaches.
   */
  public suspend fun setCamera(position: CameraPosition) {
    apply { setCamera(position) }
  }

  /**
   * Moves the camera to fit [boundingBox] with no animation.
   *
   * The fit needs a live viewport, so a call before a session attaches suspends until a session
   * attaches and applies the move, and fails with [IllegalStateException] when the state closes
   * first.
   *
   * The call returns only after [camera] holds the fitted position.
   *
   * @param padding Insets between the viewport edges and the fitted bounds.
   */
  public suspend fun fitCamera(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
  ) {
    val adapter = awaitAdapter()
    adapter.setCameraPosition(boundingBox, bearing, tilt, padding)
    apply { publishFittedCamera(adapter.getCameraPosition(), adapter.getViewport()) }
  }

  /**
   * Animates the camera to [position] over [duration] and returns when the animation ends.
   *
   * A call before a session attaches suspends until a session attaches and runs the animation. The
   * animation belongs to that session: a detach ends it, and the call returns at the position that
   * the animation reached. [close] also ends the call.
   */
  public suspend fun animateCamera(
    position: CameraPosition,
    duration: Duration = 300.milliseconds,
  ) {
    val adapter = awaitAdapter()
    adapter.animateCameraPosition(position, duration)
    apply { publishFittedCamera(adapter.getCameraPosition(), adapter.getViewport()) }
  }

  /**
   * Animates the camera to fit [boundingBox] over [duration] and returns when the animation ends.
   *
   * The fit needs a live viewport, so a call before a session attaches suspends until a session
   * attaches and runs the animation, and fails with [IllegalStateException] when the state closes
   * first. The animation belongs to that session: a detach ends it, and the call returns at the
   * position that the animation reached. [close] also ends the call.
   *
   * @param padding Insets between the viewport edges and the fitted bounds.
   */
  public suspend fun animateCameraToFit(
    boundingBox: BoundingBox,
    bearing: Double = 0.0,
    tilt: Double = 0.0,
    padding: PaddingValues = PaddingValues(0.dp),
    duration: Duration = 300.milliseconds,
  ) {
    val adapter = awaitAdapter()
    adapter.animateCameraPosition(boundingBox, bearing, tilt, padding, duration)
    apply { publishFittedCamera(adapter.getCameraPosition(), adapter.getViewport()) }
  }

  /**
   * Returns the offset from the top-left corner of the map composable that corresponds to the given
   * [position], including a [position] outside the viewport. Returns null while no attached session
   * has rendered a viewport.
   *
   * The answer describes the transform that the map has at the time of the call.
   */
  public fun screenLocationFromPosition(position: Position): DpOffset? =
    attachedAdapter?.screenLocationFromPosition(position)

  /** [screenLocationFromPosition] in pixels, the units that pointer events report. */
  public fun screenOffsetFromPosition(position: Position): Offset? =
    screenLocationFromPosition(position)?.let {
      with(host.density) { Offset(it.x.toPx(), it.y.toPx()) }
    }

  /**
   * Returns the position that corresponds to the given [offset] from the top-left corner of the map
   * composable. Returns null while no attached session has rendered a viewport.
   *
   * The answer describes the transform that the map has at the time of the call.
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position? =
    attachedAdapter?.positionFromScreenLocation(offset)

  /**
   * [positionFromScreenLocation] with an [offset] in pixels, the units that pointer events report.
   */
  public fun positionFromScreenLocation(offset: Offset): Position? =
    positionFromScreenLocation(with(host.density) { DpOffset(offset.x.toDp(), offset.y.toDp()) })

  /**
   * Returns the features rendered at the given [offset] from the top-left corner of the map
   * composable. The result is sorted by render order, so the feature in front is first in the list.
   * The list is empty while no session is attached.
   *
   * @param layerIds Limits the query to these layers; null queries every layer.
   * @param predicate Keeps only the features for which this expression is true.
   */
  public suspend fun queryRenderedFeatures(
    offset: DpOffset,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    attachedAdapter?.queryRenderedFeatures(offset, layerIds, predicate.compileOrNull())
      ?: emptyList()

  /**
   * Returns the features whose rendered geometry intersects the given [rect]. The result is sorted
   * by render order, so the feature in front is first in the list. The list is empty while no
   * session is attached.
   *
   * @param layerIds Limits the query to these layers; null queries every layer.
   * @param predicate Keeps only the features for which this expression is true.
   */
  public suspend fun queryRenderedFeatures(
    rect: DpRect,
    layerIds: Set<String>? = null,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    attachedAdapter?.queryRenderedFeatures(rect, layerIds, predicate.compileOrNull()) ?: emptyList()

  /**
   * Renders a still image of this map and returns it.
   *
   * The image shows the selected [baseStyle], the applied style composition, and the recorded
   * [camera], fit to a viewport of [width] by [height]. The state does not need a [MaplibreMap]: a
   * state constructed in a ViewModel with [setStyleComposition] can render a still image with no
   * UI. The returned bitmap is in physical pixels, [width] and [height] scaled by the state's
   * density.
   *
   * The call waits for the base style and its sources to finish loading before it renders, so the
   * image holds the fully loaded map. A style or map that fails to load fails the call with an
   * [IllegalStateException] naming the failure, and a map that never finishes loading fails the
   * same way when [timeout] passes.
   *
   * On Android, iOS, and Desktop a still image renders only while no [MaplibreMap] shows this
   * state; a call with one attached throws [IllegalStateException]. On Web this function always
   * throws [UnsupportedOperationException], because MapLibre GL JS has no still-image API. A build
   * whose packaged runtime has no still-image path — the Vulkan runtime on Android, the OpenGL
   * runtime on Desktop — also throws [UnsupportedOperationException].
   */
  public suspend fun captureStillImage(
    width: Dp,
    height: Dp,
    timeout: Duration = 30.seconds,
  ): ImageBitmap {
    require(width > 0.dp && height > 0.dp) {
      "Still image size must be positive, got $width x $height"
    }
    val (lease, effects) = kernel.reduceValue { beginCapture() }
    publishRecord()
    executeEffects(effects)
    try {
      return engine.captureStillImage(width, height, timeout)
    } finally {
      apply { finishCapture(lease, null) }
    }
  }

  private fun Expression<BooleanValue>.compileOrNull(): CompiledExpression<BooleanValue>? =
    takeUnless {
      it == const(true)
    }
    ?.compile(ExpressionContext.None)

  /** The per-composable session options; attach applies them, and a change reaches a live map. */
  internal var sessionOptions: SessionOptions? = null
    set(value) {
      if (value == field) return
      field = value
      if (value != null) attachedAdapter?.let(value::applyTo)
    }

  internal fun onStyleChanged(map: MapAdapter, style: StyleBinding?, generation: Long) {
    apply { styleChanged(map, style, generation) }
  }

  internal fun onMapDestroyed(map: MapAdapter) {
    apply { mapDestroyed(map) }
  }

  internal fun onMapFinishedLoading(map: MapAdapter, generation: Long) {
    apply { styleLoadFinished(map, generation) }
  }

  internal fun onMapFailLoading(map: MapAdapter?, reason: String, generation: Long) {
    apply { styleLoadFailed(map, generation, reason) }
  }

  internal fun onCameraMoved(map: MapAdapter) {
    val position = map.getCameraPosition()
    val viewport = map.getViewport()
    apply { cameraMoved(map, position, viewport) }
  }

  internal fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
    apply { cameraMoveStarted(map, reason) }
  }

  internal fun onCameraMoveEnded(map: MapAdapter) {
    apply { cameraMoveEnded(map) }
  }

  internal fun onSurfaceLost(map: MapAdapter, generation: Long = 0L) {
    apply { surfaceLost(map, generation) }
  }

  internal fun onSurfaceReady(map: MapAdapter, viewport: Viewport?, generation: Long = 0L) {
    apply { surfaceReady(map, generation, viewport) }
  }

  internal fun onCaptureViewport(viewport: Viewport?) {
    apply { publishCaptureViewport(viewport) }
  }

  /** Wires [adapter] into the camera; the style arrives later through [callbacks]. */
  internal fun attachSession(adapter: MapAdapter) {
    apply { attach(adapter) }
  }

  /** The engine published a retained core that may report style while no session is attached. */
  internal fun adoptCore(adapter: MapAdapter?) {
    apply { adoptCore(adapter) }
  }

  /** The engine replaced the retained core; events from the previous core are unauthorized. */
  internal fun replaceCore(adapter: MapAdapter?) {
    apply { replaceCore(adapter) }
  }

  /** Replays the selected style onto [adapter], serialized with later style selections. */
  internal fun replaySelectedStyle(adapter: MapAdapter) {
    apply { replayStyle(adapter) }
  }

  /** Unwires the session; the state, its content, and its desired style survive for the next. */
  internal fun detachSession(adapter: MapAdapter? = attachedAdapter) {
    apply { detach(adapter) }
    if (engine.detachedAdapter == null) {
      apply { styleChanged(adapter ?: Any(), null, kernel.record.styleGeneration) }
    }
  }

  /**
   * Re-points the style node at [newBinding]. Platform callbacks should use [MapStateCallbacks]
   * instead; this remains for tests that drive a binding directly.
   */
  internal fun updateBinding(newBinding: StyleBinding?) {
    val source = attachedAdapter ?: engine.detachedAdapter ?: Any()
    apply { styleChanged(source, newBinding, styleGeneration) }
  }

  /** Commits composition ownership only when [binding] is still the current style. */
  internal fun commitComposition(
    binding: StyleBinding,
    layerIds: Set<String>,
    sources: Map<String, Source>,
  ): Boolean {
    val (accepted, effects) = kernel.reduceValue { commitComposition(binding, layerIds, sources) }
    publishRecord()
    executeEffects(effects)
    return accepted
  }

  /** Commits an imperative source only when [binding] is still the current loaded style. */
  internal fun commitAppSource(binding: StyleBinding, source: Source): Boolean {
    val (accepted, effects) = kernel.reduceValue { commitAppSource(binding, source) }
    publishRecord()
    executeEffects(effects)
    if (accepted) {
      styleNode.appSources[source.id] = source
      styleNode.publishAppSources()
    }
    return accepted
  }

  internal fun commitAppSourceRemoval(binding: StyleBinding, id: String): Boolean {
    val (accepted, effects) = kernel.reduceValue { removeAppSource(binding, id) }
    publishRecord()
    executeEffects(effects)
    if (accepted) {
      styleNode.appSources.remove(id)
      styleNode.publishAppSources()
    }
    return accepted
  }

  internal fun commitAppImage(binding: StyleBinding, id: String): Boolean {
    val (accepted, effects) = kernel.reduceValue { commitAppImage(binding, id) }
    publishRecord()
    executeEffects(effects)
    if (accepted) {
      styleNode.appImages.add(id)
      styleNode.publishAppImages()
    }
    return accepted
  }

  internal fun commitAppImageRemoval(binding: StyleBinding, id: String): Boolean {
    val (accepted, effects) = kernel.reduceValue { removeAppImage(binding, id) }
    publishRecord()
    executeEffects(effects)
    if (accepted) {
      styleNode.appImages.remove(id)
      styleNode.publishAppImages()
    }
    return accepted
  }

  /**
   * Releases the map and the style composition, including a session that is still attached. The
   * owner that constructed the state calls this; [rememberMapState] closes the states it created
   * when the composition leaves. Closing is idempotent, and a closed state cannot show a map again:
   * a later attach throws [IllegalStateException], and a camera call waiting for a session fails
   * the same way instead of suspending forever.
   */
  override fun close() {
    val (alreadyClosed, effects) = kernel.reduceValue { close() }
    publishRecord()
    executeEffects(effects)
    if (alreadyClosed) return
    engine.close()
    host.close()
  }

  /** The session callbacks and the per-composition hooks they invoke. */
  internal val callbacks: MapStateCallbacks = MapStateCallbacks(this)

  private fun apply(transform: MapRecord.() -> Unit) {
    val effects = kernel.reduce {
      transform()
      publishRecord()
    }
    executeEffects(effects)
  }

  private fun publishRecord() {
    val record = kernel.record
    closedState.value = record.closed
    adapterState.value = record.session as MapAdapter?
    positionState.value = record.camera
    viewportState.value = record.viewport
    lastLoadFailure.value = record.lastLoadFailure
    moveReasonState.value = record.moveReason
    isCameraMovingState.value = record.isCameraMoving
    loadStateHolder.value = record.loadState
    pendingContent?.let { content ->
      if (!record.closed) contentState.value = content
      pendingContent = null
    }
    if (record.closed) contentState.value = EMPTY_STYLE_COMPOSITION
  }

  private fun executeEffects(effects: List<MapEffect>) {
    for (effect in effects) {
      when (effect) {
        is MapEffect.LoadStyle -> (effect.adapter as? MapAdapter)?.setBaseStyle(effect.style)
        is MapEffect.SendCamera -> (effect.adapter as? MapAdapter)?.setCameraPosition(effect.camera)
        is MapEffect.ApplySessionOptions ->
          sessionOptions?.let { options -> (effect.adapter as? MapAdapter)?.let(options::applyTo) }
        is MapEffect.PointBinding -> {
          styleNode.binding = effect.binding
          styleNode.clearPublishedAppOwnership()
          if (effect.binding === StyleBinding.UNLOADED || !effect.binding.isLoaded) {
            styleNode.clearPublishedOwnership()
            sources.clear()
          }
          host.requestApplyChanges()
        }
        MapEffect.RefreshCollections -> {
          if (!styleNode.binding.isLoaded) sources.clear() else refreshStyleCollections()
        }
        MapEffect.InvokeLoadFinished ->
          runCatching { callbacks.onMapLoadFinished() }
            .onFailure { logger?.e(it) { "The load callback threw" } }
        is MapEffect.InvokeLoadFailed ->
          runCatching { callbacks.onMapLoadFailed(effect.reason) }
            .onFailure { logger?.e(it) { "The failure callback threw" } }
        MapEffect.ResetSessionHooks -> callbacks.resetSessionHooks()
        MapEffect.ClearInheritedLocals -> inheritedLocals = null
        is MapEffect.ResumeOperation -> {}
        MapEffect.FailPendingOperations -> {}
      }
    }
  }
}

/** The single-session rule's one error message; the engine's session guard raises it too. */
internal const val SINGLE_SESSION_ERROR: String =
  "MapState already has an attached MaplibreMap; one MapState shows one MaplibreMap at a time"

/** The snapshot flavor of the single-session rule, naming the conflict the caller can end. */
internal const val SNAPSHOT_SESSION_ERROR: String =
  "MapState is rendering a still image; one MapState renders one session at a time"
