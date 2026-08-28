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

  var viewport: Viewport? = null
    private set

  var isCameraMoving: Boolean = false
    private set

  var moveReason: CameraMoveReason = CameraMoveReason.NONE
    private set

  /** The adapter that currently owns the composed session, or null. */
  var session: Any? = null
    private set

  /** The adapter that may report style events: the session, or a retained core. */
  var styleSource: Any? = null
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

  private var nextOperationId = 1L
  private var nextCaptureId = 1L

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

  private fun acceptsStyleGeneration(generation: Long): Boolean = generation == styleGeneration

  private fun emitLoad(adapter: Any, style: BaseStyle) {
    emit(MapEffect.LoadStyle(adapter, style, styleGeneration))
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
    styleSource?.let { emitLoad(it, style) }
  }

  /**
   * Grants the single session slot to [adapter], or throws. The same adapter may re-attach. A
   * retained core that already holds the current ready style is not asked to load it again.
   */
  fun attach(adapter: Any) {
    check(!closed) { "MapState is closed; a closed state cannot show a map again" }
    when (val current = renderer) {
      is RendererState.Capture -> throw IllegalStateException(SNAPSHOT_SESSION_ERROR)
      is RendererState.Session -> check(current.adapter === adapter) { SINGLE_SESSION_ERROR }
      RendererState.None -> {}
    }
    selectDemoIfNeeded()
    val previous = session
    if (previous !== adapter) hasAuthoritativeSurface = false
    val reuseLoadedStyle = adapter === styleSource && loadState is MapLoadState.Ready
    session = adapter
    styleSource = adapter
    renderer = RendererState.Session(adapter)
    if (previous !== adapter) {
      emit(MapEffect.ApplySessionOptions(adapter))
      if (!reuseLoadedStyle) selectedStyle?.let { emitLoad(adapter, it) }
      emit(MapEffect.SendCamera(adapter, camera))
    }
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

  /** The engine published or replaced the retained core that reports style while detached. */
  fun replaceCore(adapter: Any?) {
    if (closed) return
    if (adapter === styleSource && adapter === session) return
    if (session == null) {
      styleSource = adapter
      lastLoadFailure = null
      if (selectedStyle != null) {
        loadState = MapLoadState.Loading(styleGeneration, currentStyle())
        if (renderer is RendererState.Capture && adapter != null) emitLoad(adapter, currentStyle())
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
  }

  fun styleLoadFailed(source: Any?, generation: Long, reason: String) {
    if (closed) return
    if (!isStyleSource(source)) return
    if (!acceptsStyleGeneration(generation)) return
    lastLoadFailure = reason
    loadState = MapLoadState.Failed(styleGeneration, currentStyle(), reason)
    appSources = emptyMap()
    compositionSources = emptyMap()
    emit(MapEffect.RefreshCollections)
  }

  fun setCamera(position: CameraPosition) {
    if (closed) return
    camera = position
    val target =
      when (val current = renderer) {
        is RendererState.Session -> current.adapter
        is RendererState.Capture -> styleSource
        // Detached: record only. Attach and capture apply the camera after they set up the
        // renderer, so leftover session constraints cannot clamp the recorded position.
        RendererState.None -> null
      }
    target?.let { emit(MapEffect.SendCamera(it, position)) }
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

  fun surfaceLost(source: Any) {
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
    val id = nextCaptureId++
    renderer = RendererState.Capture(id)
    return id
  }

  fun finishCapture(id: Long) {
    val current = renderer as? RendererState.Capture ?: return
    if (current.id != id) return
    renderer = RendererState.None
    viewport = null
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
    if (viewport != null) this.viewport = viewport
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
    if (!binding.isLoaded) return null
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
    if (closed) return false
    if (this.binding !== binding) return false
    compositionLayerIds = layerIds
    compositionSources = sources
    return true
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

  data class Session(val adapter: Any) : RendererState

  data class Capture(val id: Long) : RendererState
}
