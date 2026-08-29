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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleCompositionHost
import org.maplibre.compose.style.StyleError
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.applyStyleRevision
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.compose.util.toStyleJson
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
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
 *
 * Logical writes run on the host dispatcher. An app with Compose Main uses that dispatcher. A
 * desktop CLI that constructs [MapState] for [captureStillImage] uses a dedicated host thread.
 * Platform callbacks post to that dispatcher and do not enter this state from the native owner
 * thread.
 */
public class MapState
internal constructor(
  cameraPosition: CameraPosition,
  density: Density = Density(1f),
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  logger: Logger? = null,
  inheritedLocals: CompositionLocalContext? = null,
  hostDispatcher: CoroutineDispatcher = defaultHostDispatcher(),
) : AutoCloseable {

  /**
   * Creates a state that owns its own camera and style wiring.
   *
   * The camera starts at [cameraPosition], and a session that attaches starts the map there. A
   * desktop caller that never starts Compose still gets a host thread for logical writes, so
   * [captureStillImage] can run from a CLI.
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

  /** The logical record for every transition this state makes. */
  internal val record = MapRecord(cameraPosition)

  private val detachedDensity = density
  private val detachedLayoutDirection = layoutDirection

  /**
   * The composition that currently writes session-scoped environment. A rival [MaplibreMap] must
   * not replace density, layout, or [sessionOptions] on a state another map already owns.
   */
  internal var sessionEnvironmentOwner: Any? = null
    private set

  private val published =
    mutableStateOf(
      PublishedMapSnapshot(
        closed = false,
        session = null,
        camera = cameraPosition,
        viewport = null,
        lastLoadFailure = null,
        moveReason = CameraMoveReason.NONE,
        isCameraMoving = false,
        loadState = MapLoadState.Idle,
        appImages = emptyList(),
      )
    )

  /**
   * The load progress of the current [baseStyle] selection. A composition that reads this property
   * recomposes when the current generation starts, finishes, or fails. An event from an earlier
   * generation never changes this value.
   */
  public val loadState: MapLoadState
    get() = published.value.loadState

  /** The attached session's adapter, or null while no session is attached. */
  internal val attachedAdapter: MapAdapter?
    get() = published.value.session

  /** True after [close]; the platform withPlatformMap actuals refuse a closed state with this. */
  internal val isClosed: Boolean
    get() = published.value.closed

  /** The last load failure reason, or null when the current generation has not failed. */
  internal val lastLoadFailure: String?
    get() = published.value.lastLoadFailure

  /**
   * The ids [StyleImages.ids] publishes. Reading this property recomposes when an image is added or
   * removed.
   */
  internal val publishedAppImages: List<String>
    get() = published.value.let { if (it.closed) emptyList() else it.appImages }

  /** The failure of the style generation [generation] frozen at capture, or null. */
  internal fun captureLoadFailure(generation: Long): String? {
    val snapshot = published.value
    return if (snapshot.capturing && snapshot.captureStyleGeneration == generation)
      snapshot.captureLoadFailure
    else null
  }

  /** True while a still-image capture holds the render slot. Off-host readers use this. */
  internal val isCapturing: Boolean
    get() = published.value.capturing

  /**
   * The failure the still-image pump must observe: a frozen-generation error stored on the lease,
   * or a current [MapLoadState.Failed] for that same generation.
   */
  internal fun captureRenderFailure(generation: Long): String? {
    captureLoadFailure(generation)?.let {
      return it
    }
    val load = loadState
    return if (load is MapLoadState.Failed && load.generation == generation) load.reason else null
  }

  /**
   * Whether a [MaplibreMap] shows this state right now. A composition that reads this property
   * recomposes when a map attaches or detaches.
   */
  public val isAttached: Boolean
    get() = published.value.session != null

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

  init {
    host.inheritedLocals = inheritedLocals
    styleNode.appSourceOwned = { id -> record.read { id in appSources } }
    record.applySessionOptions = { adapter ->
      adapter.setLayoutDirection(layoutDirection)
      sessionOptions?.applyTo(adapter)
    }
    record.pointBinding = { binding ->
      styleNode.binding = binding
      adoptOwnedSources()
      if (binding === StyleBinding.UNLOADED || !binding.isLoaded) {
        styleNode.clearPublishedOwnership()
        if (shouldClearUnloadedSources()) sources.clear()
      }
      host.requestApplyChanges()
    }
    record.refreshCollections = {
      if (styleNode.binding.isLoaded) refreshStyleCollections()
      else if (shouldClearUnloadedSources()) sources.clear()
    }
    record.resetSessionHooks = { callbacks.resetSessionHooks() }
    record.clearInheritedLocals = {
      host.inheritedLocals = null
      host.density = detachedDensity
      host.layoutDirection = detachedLayoutDirection
      sessionEnvironmentOwner = null
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
    // Read closed from the record, not the published snapshot: this runs during composition, and
    // a snapshot read would recompose rememberMapState on every load or camera change and reset
    // the baseStyle parameter over an imperative assignment.
    if (!host.runOnHostBlocking { record.mutate { replaceStyleComposition(content) } }) return
    publishStyleComposition()
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
    publishStyleComposition()
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

  internal val styleGeneration: Long
    get() = record.read { styleGeneration }

  internal val bindingGeneration: Long
    get() = record.read { bindingGeneration }

  /** The live layer named [id] when [styleGeneration] and [bindingGeneration] are still current. */
  internal fun liveLayer(styleGeneration: Long, bindingGeneration: Long, id: String): Layer? {
    val binding = currentBinding(styleGeneration, bindingGeneration) ?: return null
    return binding.getLayer(id)
  }

  /**
   * The live property named [name] on [id] when [styleGeneration] and [bindingGeneration] are still
   * current. Reads the record's binding, not a Layer descriptor that may never have attached.
   */
  internal fun liveLayerProperty(
    styleGeneration: Long,
    bindingGeneration: Long,
    id: String,
    name: String,
  ): JsonElement? {
    val binding = currentBinding(styleGeneration, bindingGeneration) ?: return null
    return binding.layerProperty(id, name)
  }

  private fun currentBinding(styleGeneration: Long, bindingGeneration: Long): StyleBinding? {
    val binding =
      record.read {
        if (this.styleGeneration != styleGeneration) return@read null
        if (this.bindingGeneration != bindingGeneration) return@read null
        binding.takeUnless { it === StyleBinding.UNLOADED }
      } ?: return null
    return binding.takeIf { it.isLoaded }
  }

  /**
   * Enqueues a layer property write only when the handle's generations are still current. The
   * platform mutation is an effect on the FIFO drain, so a rejected or superseded write never
   * publishes a half-applied value.
   */
  internal fun writeLayerProperty(
    styleGeneration: Long,
    bindingGeneration: Long,
    id: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) {
    writeAuthorizedLayer(styleGeneration, bindingGeneration, id) { binding ->
      if (!binding.isLoaded) return@writeAuthorizedLayer
      try {
        binding.setLayerProperty(id, name, value, kind)
      } catch (error: StyleMutationException) {
        logger?.w {
          "Layer '$id' kept its previous '$name': MapLibre rejected $value (${error.message})."
        }
      }
    }
  }

  /** Enqueues a layer filter write only when the handle's generations are still current. */
  internal fun writeLayerFilter(
    styleGeneration: Long,
    bindingGeneration: Long,
    id: String,
    filter: JsonElement,
  ) {
    writeAuthorizedLayer(styleGeneration, bindingGeneration, id) { binding ->
      if (binding.isLoaded) binding.setLayerFilter(id, filter)
    }
  }

  /**
   * Enqueues adapter work against the current loaded style. Source and layer definitions call it.
   */
  internal fun writeSource(write: (StyleBinding) -> Unit) {
    commit {
      val captured = binding
      if (closed || captured === StyleBinding.UNLOADED || !captured.isLoaded) return@commit
      enqueue { if (captured.isLoaded) write(captured) }
    }
  }

  internal fun setGeoJsonData(id: String, data: GeoJsonData, options: GeoJsonOptions) {
    writeSource { binding ->
      if (data is GeoJsonData.Uri) {
        binding.setGeoJsonSourceUrl(id, data.uri)
      } else {
        binding.prepareGeoJson(data, options).use { binding.setGeoJsonSourceData(id, it) }
      }
    }
  }

  internal fun setImageSourceCoordinates(id: String, coordinates: List<Position>) {
    writeSource { it.setImageSourceCoordinates(id, coordinates) }
  }

  internal fun setImageSourceImage(id: String, image: ImageBitmap) {
    writeSource { it.setImageSourceImage(id, image) }
  }

  internal fun setImageSourceUrl(id: String, url: String) {
    writeSource { it.setImageSourceUrl(id, url) }
  }

  internal fun imageSourceCoordinates(id: String): List<Position>? {
    val binding = record.read { binding.takeIf { it.isLoaded } } ?: return null
    return binding.imageSourceCoordinates(id)
  }

  internal fun invalidateCustomGeometrySourceBounds(id: String, bounds: BoundingBox) {
    writeSource { it.invalidateCustomGeometrySourceBounds(id, bounds) }
  }

  internal fun invalidateCustomGeometrySourceTile(id: String, tile: TileCoordinate) {
    writeSource { it.invalidateCustomGeometrySourceTile(id, tile) }
  }

  internal fun invalidateCustomVectorSourceTile(id: String, tile: TileCoordinate) {
    writeSource { it.invalidateCustomVectorSourceTile(id, tile) }
  }

  internal fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ) {
    writeSource { it.setFeatureState(sourceId, sourceLayerId, featureId, state) }
  }

  internal fun featureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
  ): JsonObject {
    val binding = record.read { binding.takeIf { it.isLoaded } } ?: return JsonObject(emptyMap())
    return binding.featureState(sourceId, sourceLayerId, featureId)
  }

  internal fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  ) {
    writeSource { it.removeFeatureState(sourceId, sourceLayerId, featureId, stateKey) }
  }

  internal fun resetFeatureStates(sourceId: String, sourceLayerId: String?) {
    writeSource { it.resetFeatureStates(sourceId, sourceLayerId) }
  }

  internal fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>> {
    val binding = record.read { binding.takeIf { it.isLoaded } } ?: return emptyList()
    return binding.querySourceFeatures(sourceId, sourceLayerIds, filter)
  }

  internal suspend fun clusterExpansionZoom(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): Double? {
    val binding = record.read { binding.takeIf { it.isLoaded } } ?: return null
    return binding.clusterExpansionZoom(sourceId, feature)
  }

  internal suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>? {
    val binding = record.read { binding.takeIf { it.isLoaded } } ?: return null
    return binding.clusterChildren(sourceId, feature)
  }

  internal suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>? {
    val binding = record.read { binding.takeIf { it.isLoaded } } ?: return null
    return binding.clusterLeaves(sourceId, feature, limit, offset)
  }

  private var ownedSources: Collection<Source> = emptyList()

  private fun adoptOwnedSources() {
    val next = record.read { (compositionSources.values + appSources.values).toList() }
    val nextSet = next.toHashSet()
    ownedSources.forEach { if (it !in nextSet && it.map === this) it.map = null }
    next.forEach { source ->
      val owner = source.map
      check(owner == null || owner === this) {
        "Source '${source.id}' already belongs to another MapState"
      }
      source.map = this
    }
    ownedSources = next
  }

  /** True when [owner] may write session-scoped environment onto this state. */
  internal fun acceptsSessionEnvironment(owner: Any): Boolean {
    val current = sessionEnvironmentOwner
    return current == null || current === owner
  }

  /**
   * Stores session-scoped environment when [owner] is the accepted writer. A rival composition is
   * refused, so it cannot apply padding or density to a live adapter it does not own.
   */
  internal fun publishSessionEnvironment(
    owner: Any,
    density: Density,
    layoutDirection: LayoutDirection,
    inheritedLocals: CompositionLocalContext?,
    options: SessionOptions,
    onMapClick: MapClickHandler,
    onMapLongClick: MapClickHandler,
    onFrame: (Double) -> Unit,
    clickScope: CoroutineScope,
  ): Boolean {
    if (!acceptsSessionEnvironment(owner)) return false
    sessionEnvironmentOwner = owner
    this.density = density
    this.layoutDirection = layoutDirection
    this.inheritedLocals = inheritedLocals
    sessionOptions = options
    callbacks.onMapClick = onMapClick
    callbacks.onMapLongClick = onMapLongClick
    callbacks.onFrame = onFrame
    callbacks.clickScope = clickScope
    attachedAdapter?.setLayoutDirection(layoutDirection)
    attachedAdapter?.let(options::applyTo)
    return true
  }

  private fun writeAuthorizedLayer(
    styleGeneration: Long,
    bindingGeneration: Long,
    id: String,
    write: (StyleBinding) -> Unit,
  ) {
    commit {
      val binding =
        authorizeLayerWrite(styleGeneration, bindingGeneration, id)
          ?: run {
            check(!closed) { "MapState is closed; a closed state cannot mutate the style" }
            check(
              styleGeneration == this.styleGeneration && bindingGeneration == this.bindingGeneration
            ) {
              "Layer '$id' was taken from a style that a base style load replaced; get a " +
                "fresh handle from MapState.layers"
            }
            check(!layerIsCompositionOwned(id)) {
              "Layer '$id' is owned by the style composition; change it by recomposing " +
                "the content rather than through MapState.layers"
            }
            error("No loaded style; a layer can only be mutated on a loaded style")
          }
      enqueue { write(binding) }
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
    get() = record.read { selectedStyle } ?: BaseStyle.Demo
    set(value) {
      commit { selectStyle(value) }
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
    get() = published.value.camera

  /**
   * The map's current view: the size of the rendering surface and the visible area. Null while
   * nothing renders this state; an attached session supplies it, and a [captureStillImage] in
   * progress supplies its own. A composition that reads this property recomposes after a camera
   * move or a resize of the map composable.
   */
  public val viewport: Viewport?
    get() = published.value.viewport

  /** Whether the camera is currently moving. */
  public val isCameraMoving: Boolean
    get() = published.value.isCameraMoving

  /** The reason for the most recent camera move. */
  public val cameraMoveReason: CameraMoveReason
    get() = published.value.moveReason

  /** Suspends until a session attaches, for the camera calls that need a live map. */
  private suspend fun awaitAdapter(): MapAdapter {
    val (adapter, closed) =
      snapshotFlow { attachedAdapter to published.value.closed }
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
    commitOnHost { setCamera(position) }
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
    runCameraOperation { adapter ->
      adapter.setCameraPosition(boundingBox, bearing, tilt, padding)
    }
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
    runCameraOperation { adapter -> adapter.animateCameraPosition(position, duration) }
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
    runCameraOperation { adapter ->
      adapter.animateCameraPosition(boundingBox, bearing, tilt, padding, duration)
    }
  }

  /**
   * Runs [block] as a first-class camera operation. The record publishes the adapter's resulting
   * camera before this call returns, and a cancel before [block] runs issues no later mutation.
   */
  private suspend fun runCameraOperation(block: suspend (MapAdapter) -> Unit) {
    val opId = commitOnHost { beginOperation() }
    try {
      val adapter = awaitAdapter()
      if (!commitOnHost { isOperationActive(opId) && bindOperation(opId, adapter) }) return
      block(adapter)
      if (!record.read { isOperationActive(opId) }) return
      val position = adapter.getCameraPosition()
      val viewport = adapter.getViewport()
      commitOnHost { completeCameraOperation(opId, position, viewport) }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      commitOnHost { failOperation(opId) }
      throw error
    } finally {
      commitOnHost { cancelOperation(opId) }
    }
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
   * The call freezes the selected [baseStyle] and the recorded [camera] at invocation. Later writes
   * of those values update this state and do not change that image. The call waits for that frozen
   * style and its sources to finish loading before it renders. A style or map that fails to load
   * fails the call with an [IllegalStateException] naming the failure, and a map that never
   * finishes loading fails the same way when [timeout] passes.
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
    engine.requireStillImageSupported()
    val capture = commitOnHost { beginCapture() }
    try {
      return engine.captureStillImage(width, height, timeout, capture)
    } finally {
      releaseCaptureLease(capture.id)
    }
  }

  /**
   * Releases the capture lease without a cancellable hop. A `withTimeout` or job cancel still
   * returns the renderer to [RendererState.None].
   */
  internal fun releaseCaptureLease(id: Long) {
    host.runOnHostBlocking { commitNow { finishCapture(id) } }
    host.requestApplyChanges()
  }

  private fun Expression<BooleanValue>.compileOrNull(): CompiledExpression<BooleanValue>? =
    takeUnless {
      it == const(true)
    }
    ?.compile(ExpressionContext.None)

  /** The per-composable session options; attach applies them, and a change reaches a live map. */
  internal var sessionOptions: SessionOptions? = null

  internal fun onCaptureViewport(viewport: Viewport?) {
    postLogical { publishCaptureViewport(viewport) }
  }

  /** Wires [adapter] into the camera; the style arrives later through [callbacks]. */
  internal fun attachSession(adapter: MapAdapter) {
    commit { attach(adapter) }
  }

  /** The engine replaced the retained core; events from the previous core are unauthorized. */
  internal fun replaceCore(adapter: MapAdapter?) {
    commit { replaceCore(adapter) }
  }

  /** Unwires the session; the state, its content, and its desired style survive for the next. */
  internal fun detachSession(adapter: MapAdapter? = attachedAdapter) {
    commit { detach(adapter) }
    if (engine.detachedAdapter == null) {
      val generation = record.read { styleGeneration }
      val source = adapter ?: record.read { styleSource }
      if (source != null) commit { styleChanged(source, null, generation) }
    }
  }

  /**
   * Commits the composition's desired revision, then applies it. A queued apply after close or a
   * style reload is ignored.
   */
  internal fun syncStyleComposition() {
    val binding = styleNode.binding
    val revision = styleNode.snapshotRevision()
    if (
      !commit {
        if (!commitComposition(binding, revision.layerIds, revision.sourcesById, revision.images))
          return@commit false
        enqueue { applyStyleRevision(styleNode, revision, styleNode.revisionApplier) }
        true
      }
    ) {
      return
    }
    adoptOwnedSources()
  }

  /** Commits composition ownership only when [binding] is still the current style. */
  internal fun commitComposition(
    binding: StyleBinding,
    layerIds: Set<String>,
    sources: Map<String, Source>,
    images: List<String> = emptyList(),
  ): Boolean = commit { commitComposition(binding, layerIds, sources, images) }

  /** Commits an imperative source only when [binding] is still the current loaded style. */
  internal fun commitAppSource(binding: StyleBinding, source: Source): Boolean {
    val accepted = commit { commitAppSource(binding, source) }
    if (accepted) adoptOwnedSources()
    return accepted
  }

  internal fun commitAppSourceRemoval(binding: StyleBinding, id: String): Boolean {
    val accepted = commit { removeAppSource(binding, id) }
    if (accepted) adoptOwnedSources()
    return accepted
  }

  internal fun commitAppImage(binding: StyleBinding, id: String): Boolean = commit {
    commitAppImage(binding, id)
  }

  internal fun commitAppImageRemoval(binding: StyleBinding, id: String): Boolean = commit {
    removeAppImage(binding, id)
  }

  /**
   * Releases the map and the style composition, including a session that is still attached. The
   * owner that constructed the state calls this; [rememberMapState] closes the states it created
   * when the composition leaves. Closing is idempotent, and a closed state cannot show a map again:
   * a later attach throws [IllegalStateException], and a camera call waiting for a session fails
   * the same way instead of suspending forever.
   */
  override fun close() {
    if (commit { close() }) return
    adoptOwnedSources()
    engine.close()
    host.close()
  }

  /** The session callbacks and the per-composition hooks they invoke. */
  internal val callbacks: MapStateCallbacks = MapStateCallbacks(this)

  /**
   * Applies [transform], publishes the Compose snapshot, then flushes queued effects. A reentrant
   * commit from an effect only enqueues.
   */
  internal fun <T> commit(transform: MapRecord.() -> T): T = host.runOnHostBlocking {
    commitNow(transform)
  }

  /** Applies [transform] on the calling thread. The caller is already on the host. */
  private fun <T> commitNow(transform: MapRecord.() -> T): T {
    val value = record.mutate(transform)
    publishRecord()
    record.drain()
    return value
  }

  /**
   * Commits on the host dispatcher. Suspend camera and capture APIs use this so a caller on
   * [Dispatchers.Default] cannot drain against a Main-posted platform event.
   */
  private suspend fun <T> commitOnHost(transform: MapRecord.() -> T): T = host.runOnHost {
    commitNow(transform)
  }

  /** Posts a platform event onto the host dispatcher. Native callbacks must use this. */
  internal fun postLogical(transform: MapRecord.() -> Unit) {
    host.postLogical { commitNow(transform) }
  }

  /** Runs a style mutation on the host, then publishes ownership only if the binding is current. */
  internal suspend fun runStyleEffect(effect: (StyleBinding) -> Unit) {
    host.runSerialized {
      var failure: Throwable? = null
      commitNow {
        val captured = binding
        enqueue { failure = runCatching { effect(captured) }.exceptionOrNull() }
      }
      failure?.let { throw it }
    }
  }

  private fun publishRecord() {
    val snapshot = record.read { publishedSnapshot() }
    published.value = snapshot
    publishStyleComposition()
  }

  private fun publishStyleComposition() {
    val (closed, content) = record.read { closed to styleComposition }
    contentState.value =
      if (closed || content == null) EMPTY_STYLE_COMPOSITION
      else if (contentStarted) content else contentState.value
  }

  /**
   * Keep the last loaded snapshot only while a live session is mid-reload, so attribution does not
   * flicker empty. A dead session, a close, or any load state other than Loading clears it.
   */
  private fun shouldClearUnloadedSources(): Boolean {
    val snapshot = published.value
    return snapshot.closed ||
      snapshot.session == null ||
      snapshot.loadState !is MapLoadState.Loading
  }
}

/** The single-session rule's one error message; the engine's session guard raises it too. */
internal const val SINGLE_SESSION_ERROR: String =
  "MapState already has an attached MaplibreMap; one MapState shows one MaplibreMap at a time"

/** The snapshot flavor of the single-session rule, naming the conflict the caller can end. */
internal const val SNAPSHOT_SESSION_ERROR: String =
  "MapState is rendering a still image; one MapState renders one session at a time"
