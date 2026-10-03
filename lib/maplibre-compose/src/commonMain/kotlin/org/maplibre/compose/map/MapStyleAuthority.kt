@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.checkStyleHandle
import org.maplibre.compose.style.summary

/**
 * Owns the imperative style commands, style reconciliation, and missing-image resolution of one
 * [MapState]. State changes run on the main thread; engine mutations and metadata reads run in
 * accepted owner visits and publish only while their binding is current.
 */
internal class MapStyleAuthority(
  private val lifecycle: MapLifecycleAuthority,
  private val runtime: RuntimeImplementation,
  baseStyle: BaseStyle,
) : MapStyleStateOwner {
  val style: MapStyleState = MapStyleState(baseStyle).also { it.attach(this) }

  override val resourceCommands =
    StyleResourceCommands(
      style,
      runtime.mainScope,
      commitSources = { binding, mutate -> commitSourcesAfterCommand(binding, mutate) },
      rejected = { target, error -> runtime.logger?.w(error) { "Could not $target" } },
    )

  private var baseStyleCommandRevision = 0L
  private var missingImageResolverState: MissingImageResolver? by mutableStateOf(null)
  /** Resolutions started for the loaded style, by image id. */
  private val missingImageResolutions = mutableMapOf<String, MissingImageResolution>()
  private val desiredStyleRevisionState = AtomicReference(StyleSnapshot.Empty)

  /** Read from the map owner thread too. Written on the main thread only. */
  internal var desiredStyleRevision: StyleSnapshot
    get() = desiredStyleRevisionState.load()
    private set(value) = desiredStyleRevisionState.store(value)

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

  override fun isSourceWritable(id: String): Boolean =
    desiredStyleRevision.sources.none { it.id == id }

  override fun isLayerWritable(id: String): Boolean =
    desiredStyleRevision.layers.none { it.definition.id == id }

  override fun isImageWritable(id: String): Boolean =
    desiredStyleRevision.images.none { it.id == id }

  override fun requireSourceWritable(id: String) = requireNoDesiredSource(id)

  override fun requireLayerWritable(id: String) {
    if (desiredStyleRevision.layers.any { it.definition.id == id }) {
      throw StyleHandleException("Layer ID '$id' is declared by the style content")
    }
  }

  internal suspend fun markStyleReady(adapter: MapAdapter): Boolean =
    refreshStyleResources(adapter, ready = true)

  internal suspend fun refreshStyleResources(adapter: MapAdapter, ready: Boolean = false): Boolean {
    lifecycle.requireMain()
    val binding = style.currentLoadedStyle() ?: return false
    return resourceCommands.withCommit {
      if (!acceptsResources(adapter, binding)) return@withCommit false
      if (style.loadState is StyleLoadState.Failed) return@withCommit false
      if (ready && style.hasResources(binding)) {
        style.loadState = StyleLoadState.Ready
        return@withCommit true
      }
      val resources =
        try {
          binding.awaitOwner { style.readResources(binding) } ?: return@withCommit false
        } catch (error: CancellationException) {
          throw error
        } catch (error: Throwable) {
          if (acceptsResources(adapter, binding)) throw error
          return@withCommit false
        }
      if (!acceptsResources(adapter, binding) || !publishResources(resources))
        return@withCommit false
      if (ready) style.loadState = StyleLoadState.Ready
      true
    }
  }

  private fun acceptsResources(adapter: MapAdapter, binding: StyleBinding): Boolean =
    lifecycle.acceptsAdapter(adapter) && style.isCurrentLoadedStyle(binding) && binding.isLoaded

  /** All metadata producers hold the command mutex through this main-thread publication. */
  private fun publishResources(resources: LoadedStyleResources): Boolean {
    lifecycle.requireMain()
    if (
      lifecycle.isClosed ||
        !style.isCurrentLoadedStyle(resources.binding) ||
        !resources.binding.isLoaded ||
        style.loadState is StyleLoadState.Failed
    )
      return false
    style.updateResources(resources)
    return true
  }

  internal fun updateLoadedStyle(adapter: MapAdapter, loadedStyle: StyleBinding?): Boolean {
    lifecycle.requireMain()
    if (!lifecycle.acceptsAdapter(adapter)) return false
    if (style.currentLoadedStyle() === loadedStyle) return true
    // Declarations belong to the evaluated generation. A new style is evaluated afresh, and
    // may legitimately contain a base resource with an ID used by the previous composition.
    desiredStyleRevision = StyleSnapshot.Empty
    resourceCommands.clear()
    cancelMissingImageResolutions()
    style.loadState = StyleLoadState.Loading
    style.updateLoadedStyle(loadedStyle)
    return true
  }

  override fun readyLoadedStyle(): StyleBinding? =
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }

  internal fun markStyleFailed(adapter: MapAdapter, reason: String?) {
    lifecycle.requireMain()
    if (lifecycle.acceptsAdapter(adapter)) style.loadState = StyleLoadState.Failed(reason)
  }

  /** Accepts a committed snapshot only for the loaded style that evaluated it. */
  internal suspend fun applyStyleRevision(
    adapter: MapAdapter,
    binding: StyleBinding,
    revision: StyleSnapshot,
  ) = resourceCommands.withCommit {
    currentCoroutineContext().ensureActive()
    if (!beginStyleRevision(adapter, revision, binding)) return@withCommit
    try {
      // Once accepted, commit and publish together: cancellation must not lose committed changes.
      withContext(NonCancellable) {
        val resources = adapter.reconcileStyleRevision(revision) { style.readResources(it) }
        if (acceptsResources(adapter, binding)) publishResources(resources)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      if (lifecycle.acceptsAdapter(adapter) && style.currentLoadedStyle() === binding) {
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
    desiredStyleRevision = revision
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
      baseStyleCommandRevision++
      return
    }
    style.loadState = StyleLoadState.Loading
    applyBaseStyleCommand(BaseStyleCommand(adapter, value, ++baseStyleCommandRevision))
  }

  /** Answers from any thread: source reads call it from the map owner thread. */
  override fun desiredSourceDefinition(id: String): org.maplibre.compose.style.SourceDefinition? =
    desiredStyleRevision.sources.firstOrNull { it.id == id }
      ?: resourceCommands.sourceDefinition(id)

  /** Answers from any thread: layer reads call it from the map owner thread. */
  override fun desiredLayerSummary(id: String): LayerSummary? =
    desiredStyleRevision.layers.firstOrNull { it.definition.id == id }?.definition?.summary()

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
    if (hasDesiredImage(imageId) || resourceCommands.isExplicitImage(imageId)) return null
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
      // A queued miss may arrive after another request or style command supplied the image.
      if (binding.awaitOwner { binding.imageExists(imageId) } == true) return
      val resolved =
        try {
          resolver(imageId)
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

  private suspend fun commitSourcesAfterCommand(binding: StyleBinding, mutate: () -> Unit) {
    requireStyleHandle(binding)
    val resources =
      binding.awaitOwner {
        mutate()
        style.readResources(binding)
      } ?: throw StyleHandleException("The loaded style changed before the command ran")
    requireStyleHandle(binding)
    checkStyleHandle(publishResources(resources)) { "The loaded style changed before publication" }
  }

  private fun requireNoDesiredSource(id: String) {
    if (desiredStyleRevision.sources.any { it.id == id })
      throw StyleHandleException("Source ID '$id' is declared by the style content")
  }

  private fun hasDesiredImage(id: String): Boolean = desiredStyleRevision.images.any { it.id == id }

  override fun <T> runStyleHandleOperation(
    binding: StyleBinding,
    action: () -> T,
  ): T {
    lifecycle.requireMain()
    requireStyleHandle(binding)
    val result = action()
    requireStyleHandle(binding)
    return result
  }

  private fun requireStyleHandle(binding: StyleBinding) {
    requireOpen()
    checkStyleHandle(
      style.loadState == StyleLoadState.Ready && style.isCurrentLoadedStyle(binding)
    ) {
      "Style operation belongs to a stale or unready loaded-style identity"
    }
  }

  /** Invalidates the loaded style when the map closes. */
  internal fun invalidateForClose() {
    lifecycle.requireMain()
    cancelMissingImageResolutions()
    style.invalidateLoadedStyle()
  }

  /** Invalidates the loaded style of an adapter that closed under the current presentation. */
  internal fun invalidateClosedAdapter() {
    lifecycle.requireMain()
    style.invalidateLoadedStyle()
    style.loadState = StyleLoadState.Pending
  }

  /**
   * Clears the loaded style for an adapter that has not loaded one yet. A retained engine still
   * owns its loaded style, and the presentation can return to that engine before the new adapter
   * publishes, so [retainedEngineOwnsStyle] leaves that style valid; the engine invalidates it
   * itself if it closes.
   */
  internal fun beginStyleLoadForNewAdapter(retainedEngineOwnsStyle: Boolean = false) {
    lifecycle.requireMain()
    if (retainedEngineOwnsStyle) style.updateLoadedStyle(null) else style.invalidateLoadedStyle()
    style.loadState = StyleLoadState.Loading
  }

  /** Sends the durable base style to [adapter] while it awaits publication. */
  internal fun configurePendingAdapter(adapter: MapAdapter) {
    lifecycle.requireMain()
    if (!lifecycle.isPendingPublication(adapter)) return
    applyBaseStyleCommand(BaseStyleCommand(adapter, style.baseStyle, baseStyleCommandRevision))
  }

  private fun applyBaseStyleCommand(initial: BaseStyleCommand) {
    var command = initial
    while (true) {
      if (lifecycle.currentAdapter() !== command.adapter) return
      command.adapter.setBaseStyle(command.value)
      // The adapters report the outgoing style and invalidate its binding inside setBaseStyle. Code
      // that reaches inline can assign a newer base style, which this replays.
      if (lifecycle.currentAdapter() !== command.adapter) return
      if (baseStyleCommandRevision == command.revision) return
      command = BaseStyleCommand(command.adapter, style.baseStyle, baseStyleCommandRevision)
    }
  }

  private fun requireOpen() {
    checkStyleHandle(!lifecycle.isClosed) { "The map state is closed" }
  }

  private data class BaseStyleCommand(
    val adapter: MapAdapter,
    val value: BaseStyle,
    val revision: Long,
  )
}
