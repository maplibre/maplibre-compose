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
import org.maplibre.compose.layers.LayerPropertyCompiler
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleCompositionHost
import org.maplibre.compose.style.StyleError
import org.maplibre.compose.style.StyleHostDispatcher
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.styleHostDispatcher
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** One instance, so repeated clears write the same value and the host does not recompose. */
private val EMPTY_STYLE_CONTENT: @Composable @MaplibreComposable () -> Unit = {}

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
// StyleBinding, and the wiring into the camera records and StyleSources. A session (MapAdapter)
// attaches through attachSession and detaches through detachSession; the style the session loads
// arrives through callbacks and re-points the persistent node, which reapplies the whole desired
// state to the new style.
public class MapState
internal constructor(
  cameraPosition: CameraPosition,
  density: Density = Density(1f),
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  logger: Logger? = null,
  inheritedLocals: CompositionLocalContext? = null,
  hostDispatcher: StyleHostDispatcher = styleHostDispatcher(),
) : AutoCloseable {

  /**
   * Creates a state that owns its own camera and style wiring.
   *
   * The camera starts at [cameraPosition], and a session that attaches starts the map there.
   *
   * A detached state rasterizes painters in the style content at [density] and [layoutDirection]; a
   * [MaplibreMap] that later receives this state replaces both with the composition's values.
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

  // The camera records; the session callbacks below write them and the public members read them.
  internal val adapterState = mutableStateOf<MapAdapter?>(null)

  // Snapshot-backed so a suspended awaitAdapter observes the close instead of hanging.
  private val closedState = mutableStateOf(false)
  internal val viewportState = mutableStateOf<Viewport?>(null)
  internal val positionState = mutableStateOf(cameraPosition)

  /** The most recent map load failure, surfaced by [snapshot]; cleared when a style loads. */
  internal val lastLoadFailure = mutableStateOf<String?>(null)
  internal val moveReasonState = mutableStateOf(CameraMoveReason.NONE)
  internal val isCameraMovingState = mutableStateOf(false)

  /** The attached session's adapter, or null while no session is attached. */
  internal val attachedAdapter: MapAdapter?
    get() = adapterState.value

  internal val styleNode: StyleNode = StyleNode(StyleBinding.UNLOADED, logger)

  /** Owns the map's platform lifetime; on some platforms the map outlives the composition. */
  internal val engine: MapEngine = MapEngine(this)

  internal val host: StyleCompositionHost =
    StyleCompositionHost(
      rootNode = styleNode,
      dispatcher = hostDispatcher.dispatcher,
      density = density,
      layoutDirection = layoutDirection,
      logger = logger,
      mapState = this,
      hostDispatcher = hostDispatcher,
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

  private val contentState = mutableStateOf(EMPTY_STYLE_CONTENT)

  init {
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
   * Composes empty content in place of the previous content. Writing the one [EMPTY_STYLE_CONTENT]
   * instance again is dropped by snapshot equality, so a state that never had content stays
   * untouched.
   */
  internal fun clearStyleContent() {
    contentState.value = EMPTY_STYLE_CONTENT
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

  internal fun refreshStyleCollections() {
    styleNode.refreshLiveLayerIds()
    sources.refreshSources()
  }

  /**
   * Refuses an imperative write on a layer id that the style content owns. The read is off the host
   * thread, so it takes the snapshot that the last sync published.
   */
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
      (attachedAdapter ?: engine.detachedAdapter)?.setBaseStyle(value)
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
    get() = positionState.value

  /**
   * What the map shows right now: the size of the map composable and the visible area. Null while
   * no attached session has rendered a viewport. A composition that reads this property recomposes
   * after a camera move or a resize of the map composable, because the instance is replaced once
   * the map has adopted either change.
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
    attachedAdapter?.setCameraPosition(position)
    positionState.value = position
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
    awaitAdapter().setCameraPosition(boundingBox, bearing, tilt, padding)
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
    awaitAdapter().animateCameraPosition(position, duration)
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
    awaitAdapter().animateCameraPosition(boundingBox, bearing, tilt, padding, duration)
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

  /**
   * Returns the position that corresponds to the given [offset] from the top-left corner of the map
   * composable. Returns null while no attached session has rendered a viewport.
   *
   * The answer describes the transform that the map has at the time of the call.
   */
  public fun positionFromScreenLocation(offset: DpOffset): Position? =
    attachedAdapter?.positionFromScreenLocation(offset)

  /**
   * Returns the position that corresponds to the given [offset] in pixels from the top-left corner
   * of the map composable, in the units that pointer events report. Returns null while no attached
   * session has rendered a viewport.
   *
   * The answer describes the transform that the map has at the time of the call.
   */
  public fun positionFromScreenLocation(offset: Offset): Position? =
    positionFromScreenLocation(with(host.density) { DpOffset(offset.x.toDp(), offset.y.toDp()) })

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
    attachedAdapter?.queryRenderedFeatures(offset, layerIds, predicate.compileOrNull())
      ?: emptyList()

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
    attachedAdapter?.queryRenderedFeatures(rect, layerIds, predicate.compileOrNull()) ?: emptyList()

  /**
   * Renders a still image of this map and returns it.
   *
   * The image shows the selected [baseStyle], the applied style content, and the recorded [camera],
   * fit to a viewport of [width] by [height]. The state does not need a [MaplibreMap]: a state
   * constructed in a ViewModel with [setStyleContent] can render a snapshot with no UI. The
   * returned bitmap is in physical pixels, [width] and [height] scaled by the state's density.
   *
   * The call waits for the base style and its sources to finish loading before it renders, so the
   * image holds the fully loaded map. A style or map that fails to load fails the call with an
   * [IllegalStateException] naming the failure, and a map that never finishes loading fails the
   * same way when [timeout] passes.
   *
   * On Android, iOS, and Desktop a snapshot renders only while no [MaplibreMap] shows this state; a
   * call with one attached throws [IllegalStateException]. On Web this function always throws
   * [UnsupportedOperationException], because MapLibre GL JS has no still-image API.
   */
  public suspend fun snapshot(
    width: Dp,
    height: Dp,
    timeout: Duration = 30.seconds,
  ): ImageBitmap {
    check(!closedState.value) { "MapState is closed; a closed state cannot render a snapshot" }
    require(width > 0.dp && height > 0.dp) {
      "Snapshot size must be positive, got $width x $height"
    }
    return engine.snapshot(width, height, timeout)
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

  /** Wires [adapter] into the camera; the style arrives later through [callbacks]. */
  internal fun attachSession(adapter: MapAdapter) {
    check(!closedState.value) { "MapState is closed; a closed state cannot show a map again" }
    val previous = adapterState.value
    check(previous == null || previous === adapter) { SINGLE_SESSION_ERROR }
    sessionOptions?.applyTo(adapter)
    adapterState.value = adapter
    selectedBaseStyle?.let(adapter::setBaseStyle)
    if (adapter !== previous) {
      // apply deferred state
      adapter.setCameraPosition(positionState.value)

      // usually null until the map reports its first viewport
      viewportState.value = adapter.getViewport()
    }
    sources.refreshSources()
  }

  /** Unwires the session; the state, its content, and its desired style survive for the next. */
  internal fun detachSession() {
    adapterState.value = null
    // a snapshot kept past detachment would report a viewport no map is showing
    viewportState.value = null
    // An engine that keeps the map alive keeps its loaded binding and the applied snapshot too.
    if (engine.detachedAdapter == null) updateBinding(null)
    sources.clear()
  }

  /**
   * Re-points the style node at [newBinding]. The content follows the style the application has
   * selected while the node targets the loaded style; during a switch those differ and nothing here
   * reconciles them. The binding dropping writes after unload is what makes that survivable.
   */
  internal fun updateBinding(newBinding: StyleBinding?) {
    styleNode.binding = newBinding ?: StyleBinding.UNLOADED
    refreshStyleCollections()
    host.requestApplyChanges()
  }

  /**
   * Releases the map and the style composition, including a session that is still attached. The
   * owner that constructed the state calls this; [rememberMapState] closes the states it created
   * when the composition leaves. Closing is idempotent, and a closed state cannot show a map again:
   * a later attach throws [IllegalStateException], and a camera call waiting for a session fails
   * the same way instead of suspending forever.
   */
  override fun close() {
    if (closedState.value) return
    closedState.value = true
    detachSession()
    engine.close()
    host.close()
  }

  /** The session callbacks and the per-composition hooks they invoke. */
  internal val callbacks: MapStateCallbacks = MapStateCallbacks(this)
}

/** The one statement of the single-session rule; the engine's session guard raises it too. */
internal const val SINGLE_SESSION_ERROR: String =
  "MapState already has an attached MaplibreMap; one MapState shows one MaplibreMap at a time"
