package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.MaplibreComposable

/**
 * The logical map. [mutate] is the only writer. Platform work is queued as lambdas and flushed on
 * the logical thread after the caller publishes, so a transform never calls into the adapter.
 *
 * Stale work is unauthorized by identity: a style event names a generation, a session event names
 * an adapter and a session generation, a composition publish names a binding. A superseded identity
 * is a no-op.
 */
internal class MapRecord(initialCamera: CameraPosition) {
  private val lock = newSessionLock()
  private val work = mutableListOf<() -> Unit>()
  private val effects = ArrayDeque<() -> Unit>()
  private var flushing = false

  /**
   * Applies [transform] and appends this turn's work. Do not call platform code or user callbacks
   * from [transform]; [enqueue] that work instead. The caller publishes the record, then calls
   * [drain].
   */
  fun <T> mutate(transform: MapRecord.() -> T): T = lock.withLock {
    val value = transform()
    for (task in work) effects.addLast(task)
    work.clear()
    value
  }

  /** A consistent read of the record. The block must not mutate it. */
  fun <T> read(transform: MapRecord.() -> T): T = lock.withLock { transform() }

  /** Queues [block] to run after this turn publishes. */
  fun enqueue(block: () -> Unit) {
    work += block
  }

  /**
   * Runs queued effects in enqueue order. A reentrant call returns immediately so new work sits
   * behind the flush that is already running.
   */
  fun drain() {
    if (flushing) return
    flushing = true
    try {
      while (true) {
        val task = effects.removeFirstOrNull() ?: break
        task()
      }
    } finally {
      flushing = false
    }
  }

  var applySessionOptions: (MapAdapter) -> Unit = {}
  var pointBinding: (StyleBinding) -> Unit = {}
  var refreshCollections: () -> Unit = {}
  var resetSessionHooks: () -> Unit = {}
  var clearInheritedLocals: () -> Unit = {}

  var closed: Boolean = false
    private set

  var selectedStyle: BaseStyle? = null
    private set

  var styleGeneration: Long = 0L
    private set

  var loadState: MapLoadState = MapLoadState.Idle
    private set

  var lastLoadFailure: String? = null
    private set

  var camera: CameraPosition = initialCamera
    private set

  var viewport: Viewport? = null
    private set

  var isCameraMoving: Boolean = false
    private set

  var moveReason: CameraMoveReason = CameraMoveReason.NONE
    private set

  /** The adapter that currently owns the composed session, or null. */
  var session: MapAdapter? = null
    private set

  /** The adapter that may report style events: the session, or a retained core. */
  var styleSource: MapAdapter? = null
    private set

  var hasAuthoritativeSurface: Boolean = false
    private set

  var renderer: RendererState = RendererState.None
    private set

  var binding: StyleBinding = StyleBinding.UNLOADED
    private set

  var bindingGeneration: Long = 0L
    private set

  var compositionLayerIds: Set<String> = emptySet()
    private set

  var compositionSources: Map<String, Source> = emptyMap()
    private set

  var appSources: Map<String, Source> = emptyMap()
    private set

  var appImages: List<String> = emptyList()
    private set

  var styleComposition: (@Composable @MaplibreComposable () -> Unit)? = null
    private set

  private var nextOperationId = 1L
  private var nextCaptureId = 1L
  private var nextSessionGeneration = 0L

  /**
   * The session generation that may publish programmatic camera-move events. A same-adapter
   * reattach leaves this on the previous generation until [setCamera] or [bindOperation] arms the
   * new one, so leftover native animation callbacks cannot mark the new session moving.
   */
  private var cameraWorkGeneration = 0L

  val pendingOperations: MutableMap<Long, PendingMapOperation> = mutableMapOf()

  private fun currentStyle(): BaseStyle = selectedStyle ?: BaseStyle.Demo

  private fun isStyleSource(source: MapAdapter?): Boolean = source != null && source === styleSource

  private fun currentSession(): RendererState.Session? = renderer as? RendererState.Session

  private fun isSession(source: MapAdapter?): Boolean =
    source != null && currentSession()?.adapter === source

  private fun acceptsStyleGeneration(generation: Long): Boolean = generation == styleGeneration

  private fun emitLoad(adapter: MapAdapter, style: BaseStyle) {
    val generation = styleGeneration
    enqueue { adapter.setBaseStyle(style, generation) }
  }

  private fun sendCamera(adapter: MapAdapter, position: CameraPosition) {
    enqueue { adapter.setCameraPosition(position) }
  }

  private fun selectDemoIfNeeded() {
    if (selectedStyle != null) return
    selectedStyle = BaseStyle.Demo
    styleGeneration += 1L
    loadState = MapLoadState.Loading(styleGeneration, BaseStyle.Demo)
  }

  fun selectStyle(style: BaseStyle) {
    if (closed) return
    if (style == selectedStyle) return
    selectedStyle = style
    styleGeneration += 1L
    loadState = MapLoadState.Loading(styleGeneration, style)
    lastLoadFailure = null
    // A capture renders the style it snapshotted. Later selections update logical state only.
    if (renderer !is RendererState.Capture) styleSource?.let { emitLoad(it, style) }
  }

  /**
   * Grants the single session slot to [adapter], or throws. The same adapter may re-attach. A
   * retained core that already holds the current ready style is not asked to load it again.
   */
  fun attach(adapter: MapAdapter) {
    check(!closed) { "MapState is closed; a closed state cannot show a map again" }
    when (val current = renderer) {
      is RendererState.Capture -> throw IllegalStateException(SNAPSHOT_SESSION_ERROR)
      is RendererState.Session -> check(current.adapter === adapter) { SINGLE_SESSION_ERROR }
      RendererState.None -> {}
    }
    selectDemoIfNeeded()
    val previous = session
    val retainedSame = adapter === styleSource
    val reuseLoadedStyle = retainedSame && loadState is MapLoadState.Ready
    if (previous !== adapter) {
      hasAuthoritativeSurface = false
      nextSessionGeneration += 1L
    }
    val generation = nextSessionGeneration
    session = adapter
    styleSource = adapter
    renderer = RendererState.Session(adapter, generation)
    if (previous !== adapter) {
      // A retained core already holds this adapter. Arming here would authorize leftover
      // programmatic move callbacks from the previous generation of the same adapter.
      if (!retainedSame) cameraWorkGeneration = generation
      enqueue { applySessionOptions(adapter) }
      replayStyle(adapter, reuseLoadedStyle)
      sendCamera(adapter, camera)
    } else {
      replayStyle(adapter, reuseLoadedStyle)
    }
  }

  private fun replayStyle(adapter: MapAdapter, reuseLoadedStyle: Boolean) {
    if (reuseLoadedStyle) return
    val style = selectedStyle ?: return
    if (loadState is MapLoadState.Failed) {
      styleGeneration += 1L
      lastLoadFailure = null
    }
    loadState = MapLoadState.Loading(styleGeneration, style)
    emitLoad(adapter, style)
  }

  fun detach(adapter: MapAdapter?) {
    if (adapter != null && session != null && adapter !== session) return
    if (session == null && renderer !is RendererState.Session) return
    cancelBoundOperations(adapter ?: session)
    session = null
    hasAuthoritativeSurface = false
    viewport = null
    isCameraMoving = false
    moveReason = CameraMoveReason.NONE
    renderer = RendererState.None
    enqueue { resetSessionHooks() }
    enqueue { clearInheritedLocals() }
  }

  /** The engine published or replaced the retained core that reports style while detached. */
  fun replaceCore(adapter: MapAdapter?) {
    if (closed) return
    if (adapter === styleSource && adapter === session) return
    if (session == null) {
      styleSource = adapter
      lastLoadFailure = null
      if (selectedStyle != null) {
        loadState = MapLoadState.Loading(styleGeneration, currentStyle())
        if (renderer is RendererState.Capture && adapter != null) {
          emitLoad(adapter, (renderer as RendererState.Capture).style)
        }
      }
    }
  }

  fun styleChanged(source: MapAdapter, binding: StyleBinding?, generation: Long) {
    if (closed) return
    if (!isStyleSource(source)) return
    if (!acceptsStyleGeneration(generation)) return
    val next = binding ?: StyleBinding.UNLOADED
    if (next === this.binding && bindingGeneration != 0L && binding != null) return
    bindingGeneration += 1L
    this.binding = next
    appSources = emptyMap()
    appImages = emptyList()
    if (binding == null || !next.isLoaded) {
      compositionLayerIds = emptySet()
      compositionSources = emptyMap()
    }
    if (binding != null) lastLoadFailure = null
    enqueue { pointBinding(next) }
    enqueue { refreshCollections() }
  }

  fun mapDestroyed(source: MapAdapter) {
    if (closed) return
    if (!isStyleSource(source) && !isSession(source)) return
    bindingGeneration += 1L
    binding = StyleBinding.UNLOADED
    appSources = emptyMap()
    appImages = emptyList()
    compositionLayerIds = emptySet()
    compositionSources = emptyMap()
    hasAuthoritativeSurface = false
    viewport = null
    isCameraMoving = false
    moveReason = CameraMoveReason.NONE
    enqueue { pointBinding(StyleBinding.UNLOADED) }
    enqueue { refreshCollections() }
    // The session is still attached, so a replacement map will reload this generation. Ready would
    // compare equal after that load and hide the transition that reapplies imperative mutations.
    if (session != null && selectedStyle != null) {
      loadState = MapLoadState.Loading(styleGeneration, currentStyle())
    }
  }

  fun styleLoadFinished(source: MapAdapter, generation: Long) {
    if (closed) return
    if (!isStyleSource(source)) return
    if (!acceptsStyleGeneration(generation)) return
    loadState = MapLoadState.Ready(styleGeneration, currentStyle())
    lastLoadFailure = null
    enqueue { refreshCollections() }
  }

  fun styleLoadFailed(source: MapAdapter?, generation: Long, reason: String) {
    if (closed) return
    if (!isStyleSource(source)) return
    if (!acceptsStyleGeneration(generation)) return
    lastLoadFailure = reason
    loadState = MapLoadState.Failed(styleGeneration, currentStyle(), reason)
    appSources = emptyMap()
    compositionSources = emptyMap()
    enqueue { refreshCollections() }
  }

  fun setCamera(position: CameraPosition) {
    if (closed) return
    camera = position
    val target =
      when (val current = renderer) {
        is RendererState.Session -> {
          cameraWorkGeneration = current.generation
          current.adapter
        }
        is RendererState.Capture -> null
        // Detached: record only. Attach and capture apply the camera after they set up the
        // renderer, so leftover session constraints cannot clamp the recorded position.
        RendererState.None -> null
      }
    target?.let { sendCamera(it, position) }
  }

  fun cameraMoved(source: MapAdapter, position: CameraPosition, viewport: Viewport?) {
    if (closed) return
    if (renderer is RendererState.Capture) {
      camera = position
      if (viewport != null) this.viewport = viewport
      return
    }
    val current = currentSession() ?: return
    if (current.adapter !== source) return
    // The current surface may publish its size after reattach before this generation arms camera
    // work. Position still needs that generation, so leftover animation frames cannot move it.
    val acceptPosition = isCameraMoving || cameraWorkGeneration == current.generation
    if (acceptPosition) camera = position
    if (viewport != null) {
      hasAuthoritativeSurface = true
      this.viewport = viewport
    }
  }

  fun publishCaptureViewport(viewport: Viewport?) {
    if (renderer is RendererState.Capture) this.viewport = viewport
  }

  fun cameraMoveStarted(source: MapAdapter, reason: CameraMoveReason) {
    if (closed) return
    val current = currentSession() ?: return
    if (current.adapter !== source) return
    if (reason == CameraMoveReason.PROGRAMMATIC && cameraWorkGeneration != current.generation) {
      return
    }
    moveReason = reason
    isCameraMoving = true
  }

  fun cameraMoveEnded(source: MapAdapter) {
    if (closed) return
    if (!isSession(source)) return
    isCameraMoving = false
  }

  fun surfaceLost(source: MapAdapter) {
    if (closed) return
    if (!isSession(source)) return
    hasAuthoritativeSurface = false
    viewport = null
    isCameraMoving = false
    moveReason = CameraMoveReason.NONE
  }

  fun beginCapture(): Long {
    check(!closed) { "MapState is closed; a closed state cannot render a still image" }
    check(renderer !is RendererState.Session) {
      "MapState has an attached MaplibreMap; detach it before rendering a still image"
    }
    selectDemoIfNeeded()
    check(renderer !is RendererState.Capture) { SNAPSHOT_SESSION_ERROR }
    val id = nextCaptureId++
    renderer =
      RendererState.Capture(
        id = id,
        camera = camera,
        style = currentStyle(),
        styleGeneration = styleGeneration,
      )
    // A matching retained core is not replaced, so capture must push the snapshotted style
    // itself when a core already exists and never loaded.
    styleSource?.let { emitLoad(it, currentStyle()) }
    return id
  }

  fun finishCapture(id: Long) {
    val current = renderer as? RendererState.Capture ?: return
    if (current.id != id) return
    renderer = RendererState.None
    viewport = null
    if (styleGeneration != current.styleGeneration) {
      styleSource?.let { emitLoad(it, currentStyle()) }
    }
  }

  fun beginOperation(): Long {
    val id = nextOperationId++
    pendingOperations[id] = PendingMapOperation(id)
    return id
  }

  fun bindOperation(id: Long, adapter: MapAdapter): Boolean {
    val op = pendingOperations[id] ?: return false
    if (op.cancelled) return false
    val current = currentSession() ?: return false
    if (current.adapter !== adapter) return false
    op.adapter = adapter
    op.sessionGeneration = current.generation
    cameraWorkGeneration = current.generation
    return true
  }

  private fun cancelBoundOperations(adapter: MapAdapter?) {
    if (adapter == null) return
    val stale = pendingOperations.entries.filter { it.value.adapter === adapter }
    for (entry in stale) {
      entry.value.cancelled = true
      pendingOperations.remove(entry.key)
    }
  }

  fun cancelOperation(id: Long) {
    pendingOperations.remove(id)?.let { it.cancelled = true }
  }

  fun isOperationActive(id: Long): Boolean {
    val op = pendingOperations[id] ?: return false
    return !op.cancelled
  }

  fun completeCameraOperation(id: Long, position: CameraPosition, viewport: Viewport?) {
    if (closed) {
      pendingOperations.remove(id)
      return
    }
    val op = pendingOperations.remove(id) ?: return
    if (op.cancelled) return
    val current = currentSession() ?: return
    if (op.adapter !== current.adapter) return
    if (op.sessionGeneration != current.generation) return
    camera = position
    if (viewport != null) this.viewport = viewport
  }

  fun replaceStyleComposition(
    content: (@Composable @MaplibreComposable () -> Unit)? = null
  ): Boolean {
    if (closed) return false
    if (content != null) styleComposition = content
    return true
  }

  /**
   * Authorizes an imperative layer write against the style and binding generations the handle
   * captured. Returns the current binding when the write may proceed, or null when it must not.
   */
  fun authorizeLayerWrite(
    styleGeneration: Long,
    bindingGeneration: Long,
    layerId: String,
  ): StyleBinding? {
    if (closed) return null
    if (styleGeneration != this.styleGeneration) return null
    if (bindingGeneration != this.bindingGeneration) return null
    if (layerIsCompositionOwned(layerId)) return null
    if (binding === StyleBinding.UNLOADED) return null
    return binding
  }

  fun failOperation(id: Long) {
    pendingOperations.remove(id)
  }

  /**
   * Publishes composition ownership for [binding] only when that binding is still current. A queued
   * host apply after close or a style reload is ignored.
   */
  fun commitComposition(
    binding: StyleBinding,
    layerIds: Set<String>,
    sources: Map<String, Source>,
  ): Boolean {
    if (!accepts(binding)) return false
    compositionLayerIds = layerIds
    compositionSources = sources
    return true
  }

  fun commitAppSource(binding: StyleBinding, source: Source): Boolean {
    if (!accepts(binding) || source.id in compositionSources) return false
    appSources = appSources + (source.id to source)
    return true
  }

  fun removeAppSource(binding: StyleBinding, id: String): Boolean {
    if (!accepts(binding) || id !in appSources) return false
    appSources = appSources - id
    return true
  }

  fun commitAppImage(binding: StyleBinding, id: String): Boolean {
    if (!accepts(binding)) return false
    if (id !in appImages) appImages = appImages + id
    return true
  }

  fun removeAppImage(binding: StyleBinding, id: String): Boolean {
    if (!accepts(binding) || id !in appImages) return false
    appImages = appImages - id
    return true
  }

  private fun accepts(binding: StyleBinding): Boolean = !closed && this.binding === binding

  fun layerIsCompositionOwned(id: String): Boolean = id in compositionLayerIds

  fun close(): Boolean {
    if (closed) return true
    closed = true
    styleComposition = null
    session = null
    styleSource = null
    renderer = RendererState.None
    hasAuthoritativeSurface = false
    viewport = null
    isCameraMoving = false
    moveReason = CameraMoveReason.NONE
    bindingGeneration += 1L
    binding = StyleBinding.UNLOADED
    appSources = emptyMap()
    appImages = emptyList()
    compositionLayerIds = emptySet()
    compositionSources = emptyMap()
    pendingOperations.values.forEach { it.cancelled = true }
    pendingOperations.clear()
    enqueue { resetSessionHooks() }
    enqueue { clearInheritedLocals() }
    enqueue { pointBinding(StyleBinding.UNLOADED) }
    enqueue { refreshCollections() }
    return false
  }

  /** A consistent copy of the fields [MapState] publishes to Compose. */
  fun publishedSnapshot(): PublishedMapSnapshot =
    PublishedMapSnapshot(
      closed = closed,
      session = session,
      camera = camera,
      viewport = viewport,
      lastLoadFailure = lastLoadFailure,
      moveReason = moveReason,
      isCameraMoving = isCameraMoving,
      loadState = loadState,
    )
}

/** Compose-visible fields copied under the record lock, then written after the lock is released. */
internal data class PublishedMapSnapshot(
  val closed: Boolean,
  val session: MapAdapter?,
  val camera: CameraPosition,
  val viewport: Viewport?,
  val lastLoadFailure: String?,
  val moveReason: CameraMoveReason,
  val isCameraMoving: Boolean,
  val loadState: MapLoadState,
)

/** One in-flight camera operation bound to the session generation that accepted it. */
internal class PendingMapOperation(val id: Long) {
  var cancelled: Boolean = false
  var adapter: MapAdapter? = null
  var sessionGeneration: Long = 0L
}

/** Who currently holds the render slot. */
internal sealed interface RendererState {
  data object None : RendererState

  data class Session(val adapter: MapAdapter, val generation: Long) : RendererState

  /**
   * A still capture in progress. [camera], [style], and [styleGeneration] are the logical inputs
   * frozen at [MapRecord.beginCapture]; later logical writes do not change this capture.
   */
  data class Capture(
    val id: Long,
    val camera: CameraPosition,
    val style: BaseStyle,
    val styleGeneration: Long,
  ) : RendererState
}
