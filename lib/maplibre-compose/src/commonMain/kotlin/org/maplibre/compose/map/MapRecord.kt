package org.maplibre.compose.map

import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

/**
 * The logical map record. [MapKernel] is the only writer. Platform work reports back as events on
 * this record; a superseded identity is ignored here, so a stale callback cannot change current
 * state.
 */
internal class MapRecord(initialCamera: CameraPosition) {
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

  var cameraWriteSeq: Long = 0L
    private set

  var viewport: Viewport? = null
    private set

  var isCameraMoving: Boolean = false
    private set

  var moveReason: CameraMoveReason = CameraMoveReason.NONE
    private set

  /** The adapter that currently owns the composed session, or null. */
  var session: Any? = null
    private set

  var sessionToken: Long = 0L
    private set

  /** The adapter that may report style events: the session, or a retained core. */
  var styleSource: Any? = null
    private set

  var coreGeneration: Long = 0L
    private set

  var surfaceGeneration: Long = 0L
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

  var compositionRevision: Long = 0L
    private set

  /**
   * The composable that may write session hooks and options. Independent of the adapter so a rival
   * [MaplibreMap] cannot overwrite the winner before attach.
   */
  var configOwner: Any? = null
    private set

  private var nextSessionToken = 1L
  private var nextCoreGeneration = 1L
  private var nextSurfaceGeneration = 1L
  private var nextOperationId = 1L
  private var nextCaptureId = 1L
  private var nextRevision = 1L

  val pendingOperations: MutableMap<Long, PendingMapOperation> = mutableMapOf()

  val effects: MutableList<MapEffect> = mutableListOf()

  fun takeEffects(): List<MapEffect> {
    val taken = effects.toList()
    effects.clear()
    return taken
  }

  private fun emit(effect: MapEffect) {
    effects += effect
  }

  private fun currentStyle(): BaseStyle = selectedStyle ?: BaseStyle.Demo

  private fun isStyleSource(source: Any?): Boolean = source != null && source === styleSource

  private fun isSession(source: Any?): Boolean = source != null && source === session

  private fun acceptsStyleGeneration(generation: Long): Boolean =
    generation == 0L || generation == styleGeneration

  fun selectStyle(style: BaseStyle) {
    if (closed) return
    if (style == selectedStyle) return
    selectedStyle = style
    styleGeneration += 1L
    loadState = MapLoadState.Loading(styleGeneration, style)
    lastLoadFailure = null
    styleSource?.let { emit(MapEffect.LoadStyle(it, style)) }
  }

  fun ensureStyleSelected() {
    if (closed) return
    if (selectedStyle != null) return
    selectedStyle = BaseStyle.Demo
    styleGeneration += 1L
    loadState = MapLoadState.Loading(styleGeneration, BaseStyle.Demo)
    styleSource?.let { emit(MapEffect.LoadStyle(it, BaseStyle.Demo)) }
  }

  /**
   * Grants the single session slot to [adapter], or throws. The same adapter may re-attach. Effects
   * replay camera and style after this turn; load hooks fire only when the current generation is
   * already terminal.
   */
  fun attach(adapter: Any): Long {
    check(!closed) { "MapState is closed; a closed state cannot show a map again" }
    when (val current = renderer) {
      is RendererState.Capture -> throw IllegalStateException(SNAPSHOT_SESSION_ERROR)
      is RendererState.Session -> check(current.adapter === adapter) { SINGLE_SESSION_ERROR }
      RendererState.None -> {}
    }
    val previous = session
    if (previous !== adapter) {
      sessionToken = nextSessionToken++
      surfaceGeneration = nextSurfaceGeneration++
      hasAuthoritativeSurface = false
    }
    session = adapter
    styleSource = adapter
    renderer = RendererState.Session(sessionToken, adapter)
    if (previous !== adapter) {
      emit(MapEffect.ApplySessionOptions(adapter))
      selectedStyle?.let { emit(MapEffect.LoadStyle(adapter, it)) }
      emit(MapEffect.SendCamera(adapter, camera))
      when (val load = loadState) {
        is MapLoadState.Ready -> emit(MapEffect.InvokeLoadFinished)
        is MapLoadState.Failed -> emit(MapEffect.InvokeLoadFailed(load.reason))
        else -> {}
      }
    }
    return sessionToken
  }

  fun detach(adapter: Any?) {
    if (adapter != null && session != null && adapter !== session) return
    if (session == null && renderer !is RendererState.Session) return
    session = null
    hasAuthoritativeSurface = false
    viewport = null
    isCameraMoving = false
    moveReason = CameraMoveReason.NONE
    renderer = RendererState.None
    emit(MapEffect.ResetSessionHooks)
    emit(MapEffect.ClearInheritedLocals)
  }

  /**
   * Grants [owner] the right to write session hooks and options. The first claimant wins until it
   * releases, so a rival [MaplibreMap] cannot overwrite the winner.
   */
  fun claimConfig(owner: Any): Boolean {
    if (closed) return false
    if (configOwner != null && configOwner !== owner) return false
    configOwner = owner
    return true
  }

  fun releaseConfig(owner: Any) {
    if (configOwner === owner) configOwner = null
  }

  /** The engine published or replaced the retained core that reports style while detached. */
  fun adoptCore(adapter: Any?) {
    if (closed) return
    if (adapter === styleSource && adapter === session) return
    coreGeneration = nextCoreGeneration++
    if (session == null) {
      styleSource = adapter
      if (adapter != null && loadState is MapLoadState.Ready) {
        loadState = MapLoadState.Loading(styleGeneration, currentStyle())
      }
    }
  }

  fun replaceCore(adapter: Any?) {
    if (closed) return
    coreGeneration = nextCoreGeneration++
    if (session == null) {
      styleSource = adapter
      lastLoadFailure = null
      if (selectedStyle != null) {
        loadState = MapLoadState.Loading(styleGeneration, currentStyle())
      }
    }
  }

  fun styleChanged(source: Any, binding: StyleBinding?, generation: Long) {
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
    emit(MapEffect.PointBinding(next))
    emit(MapEffect.RefreshCollections)
  }

  fun mapDestroyed(source: Any) {
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
    emit(MapEffect.PointBinding(StyleBinding.UNLOADED))
    emit(MapEffect.RefreshCollections)
  }

  fun styleLoadFinished(source: Any, generation: Long) {
    if (closed) return
    if (!isStyleSource(source)) return
    if (!acceptsStyleGeneration(generation)) return
    loadState = MapLoadState.Ready(styleGeneration, currentStyle())
    lastLoadFailure = null
    emit(MapEffect.RefreshCollections)
    if (session != null) emit(MapEffect.InvokeLoadFinished)
  }

  fun styleLoadFailed(source: Any?, generation: Long, reason: String) {
    if (closed) return
    if (source != null && !isStyleSource(source)) return
    if (!acceptsStyleGeneration(generation)) return
    lastLoadFailure = reason
    loadState = MapLoadState.Failed(styleGeneration, currentStyle(), reason)
    appSources = emptyMap()
    compositionSources = emptyMap()
    emit(MapEffect.RefreshCollections)
    if (session != null) emit(MapEffect.InvokeLoadFailed(reason))
  }

  fun setCamera(position: CameraPosition) {
    if (closed) return
    camera = position
    cameraWriteSeq += 1L
    val target =
      when (val current = renderer) {
        is RendererState.Session -> current.adapter
        is RendererState.Capture -> styleSource
        RendererState.None -> styleSource
      }
    target?.let { emit(MapEffect.SendCamera(it, position)) }
  }

  /** Publishes a completed camera operation before the caller resumes. */
  fun publishFittedCamera(position: CameraPosition, viewport: Viewport?) {
    if (closed) return
    camera = position
    cameraWriteSeq += 1L
    if (viewport != null) this.viewport = viewport
  }

  /** Replays the current selection onto [adapter] without starting a new generation. */
  fun replayStyle(adapter: Any) {
    if (closed) return
    selectedStyle?.let { emit(MapEffect.LoadStyle(adapter, it)) }
  }

  fun cameraMoved(source: Any, position: CameraPosition, viewport: Viewport?) {
    if (closed) return
    if (renderer is RendererState.Capture) {
      camera = position
      if (viewport != null) this.viewport = viewport
      return
    }
    if (!isSession(source)) return
    camera = position
    if (viewport != null) {
      hasAuthoritativeSurface = true
      this.viewport = viewport
    }
  }

  fun publishCaptureViewport(viewport: Viewport?) {
    if (renderer is RendererState.Capture) this.viewport = viewport
  }

  fun cameraMoveStarted(source: Any, reason: CameraMoveReason) {
    if (closed) return
    if (!isSession(source)) return
    moveReason = reason
    isCameraMoving = true
  }

  fun cameraMoveEnded(source: Any) {
    if (closed) return
    if (!isSession(source)) return
    isCameraMoving = false
  }

  fun surfaceLost(source: Any, generation: Long) {
    if (closed) return
    if (!isSession(source)) return
    if (generation != 0L && generation != surfaceGeneration) return
    hasAuthoritativeSurface = false
    viewport = null
    isCameraMoving = false
    moveReason = CameraMoveReason.NONE
  }

  fun surfaceReady(source: Any, generation: Long, viewport: Viewport?) {
    if (closed) return
    if (!isSession(source)) return
    if (generation != 0L && generation != surfaceGeneration) return
    hasAuthoritativeSurface = true
    this.viewport = viewport
  }

  fun retargetSurface(source: Any) {
    if (closed) return
    if (!isSession(source)) return
    surfaceGeneration = nextSurfaceGeneration++
    hasAuthoritativeSurface = false
    viewport = null
  }

  fun beginCapture(): Long {
    check(!closed) { "MapState is closed; a closed state cannot render a still image" }
    check(renderer !is RendererState.Session) {
      "MapState has an attached MaplibreMap; detach it before rendering a still image"
    }
    val id = nextCaptureId++
    renderer = RendererState.Capture(id)
    return id
  }

  fun finishCapture(id: Long, viewport: Viewport?) {
    if (renderer is RendererState.Capture && (renderer as RendererState.Capture).id == id) {
      renderer = RendererState.None
      this.viewport = null
    }
    if (viewport != null && renderer is RendererState.Capture) this.viewport = viewport
  }

  fun beginOperation(): Long {
    val id = nextOperationId++
    pendingOperations[id] = PendingMapOperation(id)
    return id
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
    camera = position
    cameraWriteSeq += 1L
    if (viewport != null) this.viewport = viewport
    emit(MapEffect.ResumeOperation(id, Result.success(Unit)))
  }

  /**
   * Authorizes an imperative layer write against [generation]. Returns the current binding when the
   * write may proceed, or null when it must not.
   */
  fun authorizeLayerWrite(generation: Long, layerId: String): StyleBinding? {
    if (closed) return null
    if (generation != styleGeneration) return null
    if (layerIsCompositionOwned(layerId)) return null
    if (!binding.isLoaded) return null
    return binding
  }

  /** True when [generation] and [binding] are still the live style the write was authorized on. */
  fun confirmLayerWrite(generation: Long, binding: StyleBinding): Boolean =
    !closed && generation == styleGeneration && this.binding === binding

  fun failOperation(id: Long, error: Throwable) {
    pendingOperations.remove(id) ?: return
    emit(MapEffect.ResumeOperation(id, Result.failure(error)))
  }

  /**
   * Publishes composition ownership for [binding] only when that binding is still current. A queued
   * host apply after close or a style reload is ignored.
   */
  fun commitComposition(
    binding: StyleBinding,
    layerIds: Set<String>,
    sources: Map<String, Source>,
    revision: Long = 0L,
  ): Boolean {
    if (closed) return false
    if (this.binding !== binding) return false
    if (revision != 0L && revision < compositionRevision) return false
    if (revision != 0L) compositionRevision = revision
    compositionLayerIds = layerIds
    compositionSources = sources
    return true
  }

  fun nextCompositionRevision(): Long {
    val revision = nextRevision++
    compositionRevision = revision
    return revision
  }

  fun commitAppSource(binding: StyleBinding, source: Source): Boolean {
    if (closed) return false
    if (this.binding !== binding || !binding.isLoaded) return false
    if (source.id in compositionSources) return false
    appSources = appSources + (source.id to source)
    return true
  }

  fun removeAppSource(binding: StyleBinding, id: String): Boolean {
    if (closed) return false
    if (this.binding !== binding) return false
    if (id !in appSources) return false
    appSources = appSources - id
    return true
  }

  fun commitAppImage(binding: StyleBinding, id: String): Boolean {
    if (closed) return false
    if (this.binding !== binding || !binding.isLoaded) return false
    if (id in appImages) return true
    appImages = appImages + id
    return true
  }

  fun removeAppImage(binding: StyleBinding, id: String): Boolean {
    if (closed) return false
    if (this.binding !== binding) return false
    if (id !in appImages) return false
    appImages = appImages - id
    return true
  }

  fun layerIsCompositionOwned(id: String): Boolean = id in compositionLayerIds

  fun close(): Boolean {
    if (closed) return true
    closed = true
    session = null
    styleSource = null
    configOwner = null
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
    emit(MapEffect.ResetSessionHooks)
    emit(MapEffect.ClearInheritedLocals)
    emit(MapEffect.PointBinding(StyleBinding.UNLOADED))
    emit(MapEffect.RefreshCollections)
    emit(MapEffect.FailPendingOperations)
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

/** Compose-visible fields copied under the kernel lock, then written after the lock is released. */
internal data class PublishedMapSnapshot(
  val closed: Boolean,
  val session: Any?,
  val camera: CameraPosition,
  val viewport: Viewport?,
  val lastLoadFailure: String?,
  val moveReason: CameraMoveReason,
  val isCameraMoving: Boolean,
  val loadState: MapLoadState,
)

/** One in-flight camera or capture operation the kernel owns. */
internal class PendingMapOperation(val id: Long) {
  var cancelled: Boolean = false
}

/** Who currently holds the render slot. */
internal sealed interface RendererState {
  data object None : RendererState

  data class Session(val token: Long, val adapter: Any) : RendererState

  data class Capture(val id: Long) : RendererState
}
