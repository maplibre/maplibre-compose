package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GroundScaleGlobalState
import org.maplibre.compose.style.GroundScaleTolerance
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.groundScale

/**
 * Owns the imperative style commands, style reconciliation, and missing-image resolution of one
 * [MapState]. Everything runs on main. Writes are submitted in order, and handles are published
 * from what main submitted, while their loaded style is current.
 */
internal class MapStyleAuthority(
  private val lifecycle: MapLifecycleAuthority,
  private val runtime: MapRuntime,
  baseStyle: BaseStyle,
) : MapStyleStateOwner {
  val style: MapStyleState = MapStyleState(baseStyle).also { it.attach(this) }

  override val resourceCommands = StyleResourceCommands(style)

  override val logger: MapLog?
    get() = runtime.logger

  private var groundScale: Float? = null
  /** The style and value of the last ground-scale write. */
  private var writtenGroundScale: Pair<StyleBinding, Float>? = null
  // Referential: an equal but distinct resolver still replaces the current one.
  private var missingImageResolverState: MissingImageResolver? by
    mutableStateOf(null, referentialEqualityPolicy())
  /** Resolutions started for the loaded style, by image id. */
  private val missingImageResolutions = mutableMapOf<String, MissingImageResolution>()
  internal var missingImageResolver: MissingImageResolver?
    get() = missingImageResolverState
    set(value) {
      lifecycle.requireMain()
      if (missingImageResolverState === value) return
      missingImageResolverState = value
      // Forgotten rather than cancelled: the request that started a resolution in flight is still
      // outstanding, and the engine waits on that resolution rather than asking the new resolver.
      missingImageResolutions.clear()
    }

  /**
   * Publishes the loaded style's handles and marks it ready. Source metadata that the engine
   * changed while the style loaded, such as TileJSON attribution, is then read once more.
   */
  internal fun markStyleReady(adapter: MapAdapter) {
    lifecycle.requireMain()
    val binding = style.currentLoadedStyle() ?: return
    if (!acceptsResources(adapter, binding) || !publishResources(binding)) return
    style.loadState = StyleLoadState.Ready
    writeGroundScale()
    runtime.mainScope.launch {
      try {
        style.refreshSources(binding)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        if (acceptsResources(adapter, binding)) {
          runtime.logger?.w(error) { "Could not read the loaded style's sources" }
        }
      }
    }
  }

  /** Publishes new engine metadata for source [id], such as attribution from its TileJSON. */
  internal suspend fun refreshStyleSource(adapter: MapAdapter, id: String) {
    lifecycle.requireMain()
    val binding = style.currentLoadedStyle() ?: return
    if (!acceptsResources(adapter, binding)) return
    style.refreshSource(binding, id)
  }

  private fun acceptsResources(adapter: MapAdapter, binding: StyleBinding): Boolean =
    lifecycle.acceptsAdapter(adapter) && style.isCurrentLoadedStyle(binding) && binding.isLoaded

  /** Publishes the handles of [binding] while it remains the loaded style. */
  private fun publishResources(binding: StyleBinding): Boolean {
    lifecycle.requireMain()
    if (
      lifecycle.isClosed ||
        !style.isCurrentLoadedStyle(binding) ||
        !binding.isLoaded ||
        style.loadState is StyleLoadState.Failed
    )
      return false
    style.updateResources(style.resources(binding))
    return true
  }

  internal fun updateLoadedStyle(adapter: MapAdapter, loadedStyle: StyleBinding?): Boolean {
    lifecycle.requireMain()
    if (!lifecycle.acceptsAdapter(adapter)) return false
    if (style.currentLoadedStyle() === loadedStyle) return true
    resourceCommands.clear()
    cancelMissingImageResolutions()
    writtenGroundScale = null
    style.loadState = StyleLoadState.Loading
    style.updateLoadedStyle(loadedStyle)
    return true
  }

  /**
   * Records the ground scale at [latitude] and writes it to the ready style once it differs from
   * the last write by more than [GroundScaleTolerance]. The write is one global-state command,
   * outside style revisions.
   */
  internal fun updateGroundScale(latitude: Double) {
    lifecycle.requireMain()
    groundScale = groundScale(latitude)
    writeGroundScale()
  }

  private fun writeGroundScale() {
    val value = groundScale ?: return
    val binding = readyLoadedStyle() ?: return
    val written = writtenGroundScale
    if (
      written != null &&
        written.first === binding &&
        abs(value / written.second - 1f) <= GroundScaleTolerance
    )
      return
    writtenGroundScale = binding to value
    val json = JsonPrimitive(value)
    binding.setGlobalStateProperty(GroundScaleGlobalState, json)
  }

  override fun readyLoadedStyle(): StyleBinding? =
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }

  internal fun markStyleFailed(adapter: MapAdapter, reason: String?) {
    lifecycle.requireMain()
    if (lifecycle.acceptsAdapter(adapter)) style.loadState = StyleLoadState.Failed(reason)
  }

  /**
   * Accepts a committed snapshot only for the loaded style that evaluated it. When the engine
   * rejects part of it, the latest revision is applied again: the rejected definitions stay out,
   * and what they had blocked goes in.
   */
  internal suspend fun applyStyleRevision(
    adapter: MapAdapter,
    binding: StyleBinding,
    revision: StyleSnapshot,
  ) {
    currentCoroutineContext().ensureActive()
    if (!beginStyleRevision(adapter, revision, binding)) return
    try {
      withContext(NonCancellable) {
        var next = revision
        repeat(MaxStyleRevisionPasses) {
          if (!acceptsResources(adapter, binding)) return@withContext
          val rejected = adapter.reconcileStyleRevision(next)
          if (!acceptsResources(adapter, binding)) return@withContext
          publishResources(binding)
          if (!rejected) return@withContext
          next = style.declaredRevision
        }
        runtime.logger?.w {
          "Style content still had rejected writes after $MaxStyleRevisionPasses passes"
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      if (acceptsResources(adapter, binding)) {
        runtime.logger?.w(error) { "Could not apply style content" }
        markStyleFailed(adapter, error.message)
      }
    }
  }

  private fun beginStyleRevision(
    adapter: MapAdapter,
    revision: StyleSnapshot,
    binding: StyleBinding,
  ): Boolean {
    lifecycle.requireMain()
    if (
      !lifecycle.acceptsAdapter(adapter) ||
        style.currentLoadedStyle() !== binding ||
        !binding.isLoaded
    )
      return false
    resourceCommands.requireNoConflicts(revision)
    if (style.loadState is StyleLoadState.Failed) style.loadState = StyleLoadState.Loading
    style.declaredRevision = revision
    return true
  }

  override fun setBaseStyle(value: BaseStyle) {
    lifecycle.requireMain()
    requireOpen()
    if (style.baseStyle == value) return
    resourceCommands.clear()
    cancelMissingImageResolutions()
    style.setBaseStyleState(value)
    style.invalidateLoadedStyle()
    val adapter = lifecycle.currentAdapter()
    if (adapter == null) {
      style.loadState = StyleLoadState.Pending
      return
    }
    style.loadState = StyleLoadState.Loading
    adapter.setBaseStyle(value)
  }

  /**
   * Starts resolution of a missing style image and returns the resolution, or null when no resolver
   * is set, no style is loaded, or this state no longer accepts [adapter].
   *
   * A repeated request for an id already resolving returns that resolution, which the browser needs
   * to keep the request pending while the first call runs.
   *
   * The resolution runs on the runtime's main scope and consults the engine off it.
   */
  internal fun resolveMissingImage(adapter: MapAdapter, imageId: String): Deferred<Unit>? {
    lifecycle.requireMain()
    if (lifecycle.isClosed || !lifecycle.acceptsAdapter(adapter)) return null
    val resolver = missingImageResolverState ?: return null
    val binding = style.currentLoadedStyle() ?: return null
    if (!style.isImageWritable(imageId) || resourceCommands.isExplicitImage(imageId)) return null
    missingImageResolutions[imageId]?.let {
      return it.work
    }
    val token = Any()
    return runtime.mainScope
      .async(start = CoroutineStart.LAZY) {
        supplyMissingImage(resolver, binding, imageId, token)
      }
      .also {
        // Register before starting: even an inline completion must be able to remove its record.
        missingImageResolutions[imageId] = MissingImageResolution(token, it)
        it.start()
      }
  }

  private suspend fun supplyMissingImage(
    resolver: MissingImageResolver,
    binding: StyleBinding,
    imageId: String,
    token: Any,
  ) {
    var rememberFailure = false
    try {
      if (binding.imageExists(imageId) == true) return
      val resolved =
        try {
          resolver.resolve(MissingImageRequest(imageId))
        } catch (error: CancellationException) {
          throw error
        } catch (error: Throwable) {
          runtime.logger?.w(error) { "The missing-image resolver failed for image '$imageId'" }
          null
        }
      if (resolved == null) {
        rememberFailure = true
        return
      }
      resourceCommands.supply(binding, imageId, resolved)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      // A style switch invalidates the binding mid-write; that failure is expected, not a warning.
      if (style.isCurrentLoadedStyle(binding))
        runtime.logger?.w(error) { "Could not add the resolved image '$imageId'" }
    } finally {
      // Keep negative results to avoid a request loop, but let a later engine miss restore an
      // evicted image. An older resolver must not clear a replacement resolver's pending work.
      if (!rememberFailure && missingImageResolutions[imageId]?.token === token) {
        missingImageResolutions.remove(imageId)
      }
    }
  }

  /** Ends every resolution in flight: none of them can still reach the style that asked. */
  private fun cancelMissingImageResolutions() {
    missingImageResolutions.values.forEach { it.work.cancel() }
    missingImageResolutions.clear()
  }

  override fun isCurrent(binding: StyleBinding): Boolean =
    !lifecycle.isClosed && style.isReadyBinding(binding)

  /** Invalidates the loaded style when the map closes. */
  internal fun invalidateForClose() {
    lifecycle.requireMain()
    cancelMissingImageResolutions()
    style.invalidateLoadedStyle()
  }

  internal fun beginStyleLoadForNewAdapter() {
    lifecycle.requireMain()
    style.invalidateLoadedStyle()
    style.loadState = StyleLoadState.Loading
  }

  /** Sends the durable base style to [adapter] while it awaits publication. */
  internal fun configurePendingAdapter(adapter: MapAdapter) {
    lifecycle.requireMain()
    if (!lifecycle.isPendingPublication(adapter)) return
    adapter.setBaseStyle(style.baseStyle)
  }

  override fun requireOpen() {
    lifecycle.requireMain()
    check(!lifecycle.isClosed) { "The map state is closed" }
  }
}

/**
 * How many times one revision is applied while the engine rejects part of it. Each pass skips the
 * definitions an earlier pass saw rejected, so a few passes settle any revision.
 */
internal const val MaxStyleRevisionPasses = 8
