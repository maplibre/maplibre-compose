package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceHandle
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.StyleResourceChanges
import org.maplibre.compose.util.ImageStretch

/**
 * Owns the imperative style commands, style reconciliation, and missing-image resolution of one
 * [MapState]. Every mutation runs under [lifecycle]'s lock; engine reads run outside it and commit
 * only while their generation is still current.
 */
internal class MapStyleAuthority(
  private val lifecycle: MapLifecycleAuthority,
  private val runtime: RuntimeImplementation,
  baseStyle: BaseStyle,
) : MapStyleStateOwner {
  val style: MapStyleState = MapStyleState(baseStyle).also { it.attach(this) }
  /** Engine reads block until the owner thread answers, so they run off the calling dispatcher. */
  private val readDispatcher: CoroutineDispatcher =
    runtime.physicalScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
      ?: Dispatchers.Default

  private var baseStyleCommandRevision = 0L
  private var styleHandleEpoch = 0L
  private var styleSourceChangeRevision = 0L
  private val imperativeSources = mutableMapOf<String, ImperativeSourceRecord>()
  private val imperativeImages = mutableMapOf<String, ImperativeImageRecord>()
  private var missingImageResolverState: MissingImageResolver? by mutableStateOf(null)
  /** Resolutions started for the loaded style, by image id. */
  private val missingImageResolutions = mutableMapOf<String, MissingImageResolution>()
  private var activeStyleMutation: StyleMutationReservation? = null
  /**
   * Held while a resolved missing image reaches the style. A style command the app issues cannot
   * anticipate this one, so the command waits it out through [beginStyleRevision] or runs beside it
   * rather than failing on it.
   */
  private var backgroundStyleMutation: StyleMutationReservation? = null

  internal var desiredStyleRevision: DesiredStyleRevision = DesiredStyleRevision.Empty

  internal var missingImageResolver: MissingImageResolver?
    get() = missingImageResolverState
    set(value) {
      lifecycle.serialized {
        if (missingImageResolverState === value) return
        missingImageResolverState = value
        // Forgotten rather than cancelled: the request that started a resolution in flight is still
        // outstanding, and the engine waits on that resolution rather than asking the new resolver.
        missingImageResolutions.clear()
      }
    }

  override fun isSourceWritable(id: String): Boolean = lifecycle.serialized {
    desiredStyleRevision.sources.none { it.id == id }
  }

  override fun isLayerWritable(id: String): Boolean = lifecycle.serialized {
    desiredStyleRevision.layers.none { it.definition.id == id }
  }

  override fun isImageWritable(id: String): Boolean = lifecycle.serialized {
    desiredStyleRevision.images.none { it.id == id }
  }

  override fun requireSourceWritable(id: String) = lifecycle.serialized {
    requireNoDesiredSource(id)
  }

  override fun requireLayerWritable(id: String) = lifecycle.serialized {
    if (desiredStyleRevision.layers.any { it.definition.id == id }) {
      throw StyleHandleException("Layer ID '$id' is declared by the style content")
    }
  }

  internal suspend fun markStyleReady(adapter: MapAdapter): Boolean {
    while (true) {
      val read = lifecycle.serialized {
        if (!lifecycle.acceptsAdapter(adapter)) return false
        if (style.loadState == StyleLoadState.Ready) return true
        val binding = style.currentLoadedStyle() ?: return false
        StyleResourceRead(binding, styleHandleEpoch, styleSourceChangeRevision)
      }
      val resources =
        readWhileCurrent(adapter, read) { style.readResources(read.binding) } ?: return false
      val committed = lifecycle.serialized {
        if (!isCurrentStyleResourceRead(adapter, read)) return false
        if (style.loadState is StyleLoadState.Failed) return false
        if (styleSourceChangeRevision != read.sourceChangeRevision) return@serialized false
        style.updateResources(resources)
        style.loadState = StyleLoadState.Ready
        true
      }
      if (committed) return true
    }
  }

  internal suspend fun refreshStyleSources(adapter: MapAdapter, sourceId: String? = null): Boolean {
    lifecycle.serialized {
      if (!lifecycle.acceptsAdapter(adapter)) return false
      styleSourceChangeRevision++
    }
    while (true) {
      val read = lifecycle.serialized {
        if (!lifecycle.acceptsAdapter(adapter)) return false
        if (style.loadState != StyleLoadState.Ready) return true
        val binding = style.currentLoadedStyle() ?: return true
        StyleResourceRead(binding, styleHandleEpoch, styleSourceChangeRevision)
      }
      val sources =
        readWhileCurrent(adapter, read) { style.readSources(read.binding, sourceId) }
          ?: return false
      val committed = lifecycle.serialized {
        if (!isCurrentStyleResourceRead(adapter, read)) return false
        if (style.loadState != StyleLoadState.Ready) return false
        if (styleSourceChangeRevision != read.sourceChangeRevision) return@serialized false
        style.updateSources(sources)
        true
      }
      if (committed) return true
    }
  }

  internal suspend fun updateStyleResources(adapter: MapAdapter, changes: StyleResourceChanges) {
    if (changes.sources.isEmpty() && changes.layerOrder == null) return
    val read = lifecycle.serialized {
      if (!lifecycle.acceptsAdapter(adapter) || style.loadState != StyleLoadState.Ready) return
      val binding = style.currentLoadedStyle() ?: return
      if (binding.identity !== changes.identity) return
      StyleResourceRead(binding, styleHandleEpoch, styleSourceChangeRevision)
    }
    // The engine already holds these changes. A newer revision cancelling the caller must not
    // leave them unpublished: it would report no structural change and never repair the handles.
    withContext(NonCancellable) {
      changes.sources.forEach { refreshStyleSources(adapter, it) }
      val layers =
        readWhileCurrent(adapter, read) { style.readLayers(read.binding, changes.layers) }
          ?: return@withContext
      lifecycle.serialized {
        if (!isCurrentStyleResourceRead(adapter, read)) return@withContext
        changes.layerOrder?.let { style.updateLayers(layers, it) }
      }
    }
  }

  /**
   * Runs an engine read on [readDispatcher]. A failure is rethrown while [read] is still current
   * and yields null once it is not, because nothing waits on a generation that is gone.
   */
  private suspend fun <T> readWhileCurrent(
    adapter: MapAdapter,
    read: StyleResourceRead,
    block: () -> T,
  ): T? =
    try {
      withContext(readDispatcher) { block() }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      if (lifecycle.serialized { isCurrentStyleResourceRead(adapter, read) }) throw error
      null
    }

  private fun isCurrentStyleResourceRead(adapter: MapAdapter, read: StyleResourceRead): Boolean =
    lifecycle.acceptsAdapter(adapter) &&
      styleHandleEpoch == read.styleHandleEpoch &&
      style.currentLoadedStyle() === read.binding

  internal fun updateLoadedStyle(adapter: MapAdapter, loadedStyle: StyleBinding?): Boolean =
    lifecycle.serialized {
      if (!lifecycle.acceptsAdapter(adapter)) return false
      if (style.currentLoadedStyle() === loadedStyle) return true
      styleHandleEpoch++
      imperativeSources.clear()
      imperativeImages.clear()
      cancelMissingImageResolutions()
      style.loadState = StyleLoadState.Loading
      style.updateLoadedStyle(loadedStyle)
      true
    }

  override fun readyLoadedStyle(): StyleBinding? = lifecycle.serialized {
    style.currentLoadedStyle()?.takeIf { style.loadState == StyleLoadState.Ready }
  }

  internal fun markStyleFailed(adapter: MapAdapter, reason: String?) {
    lifecycle.serialized {
      if (lifecycle.acceptsAdapter(adapter)) {
        style.loadState = StyleLoadState.Failed(reason)
      }
    }
  }

  internal suspend fun beginStyleRevision(adapter: MapAdapter, revision: DesiredStyleRevision) {
    while (true) {
      val mutation = lifecycle.serialized {
        if (!lifecycle.acceptsAdapter(adapter)) return
        activeStyleMutation
          ?: backgroundStyleMutation
          ?: run {
            requireNoImperativeResourceConflicts(revision)
            styleHandleEpoch++
            if (style.loadState is StyleLoadState.Failed) {
              style.loadState = StyleLoadState.Loading
            }
            desiredStyleRevision = revision
            return
          }
      }
      mutation.completion.await()
    }
  }

  override fun setBaseStyle(value: BaseStyle) {
    val command = lifecycle.serialized {
      requireOpenLocked()
      if (style.baseStyle == value) return
      requireNoActiveStyleMutation()
      styleHandleEpoch++
      imperativeSources.clear()
      imperativeImages.clear()
      cancelMissingImageResolutions()
      style.setBaseStyleState(value)
      style.invalidateLoadedStyle()
      val adapter = lifecycle.currentAdapter()
      if (adapter == null) {
        style.loadState = StyleLoadState.Pending
        baseStyleCommandRevision++
        return
      } else {
        style.loadState = StyleLoadState.Loading
      }
      BaseStyleCommand(adapter, value, ++baseStyleCommandRevision)
    }
    applyBaseStyleCommand(command)
  }

  override fun desiredSourceDefinition(id: String): org.maplibre.compose.style.SourceDefinition? =
    lifecycle.serialized {
      desiredStyleRevision.sources.firstOrNull { it.id == id } ?: imperativeSources[id]?.definition
    }

  override fun addStyleSource(source: Source): SourceHandle {
    val definition = source.definition()
    val record = ImperativeSourceRecord(definition)
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireNoDesiredSource(source.id)
      requireNoActiveStyleMutation()
      if (source.id in imperativeSources) {
        throw StyleHandleException("Source ID '${source.id}' already exists in style")
      }
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        imperativeSources[source.id] = record
        activeStyleMutation = reservation
      }
    }
    var committed = false
    try {
      if (binding.sourceExists(source.id) == true) {
        throw StyleHandleException("Source ID '${source.id}' already exists in style")
      }
      val added = binding.addSource(definition)
      if (!added) throw IllegalStateException("The loaded-style generation changed during add")
      lifecycle.serialized { requireStyleHandleLocked(binding) }
      val handle = checkNotNull(refreshSourcesAfterCommand(binding)[source.id])
      committed = true
      return handle
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not add source '${source.id}': ${error.message}", error)
    } finally {
      lifecycle.serialized {
        if (!committed && imperativeSources[source.id] === record) {
          imperativeSources.remove(source.id)
        }
        completeStyleMutation(reservation)
      }
    }
  }

  override fun removeStyleSource(id: String, expectedStyle: StyleBinding, identity: Any): Boolean {
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireStyleHandleLocked(expectedStyle)
      check(expectedStyle.identity.sources.isCurrent(id, identity)) {
        "Source '$id' has been removed or replaced"
      }
      requireNoDesiredSource(id)
      requireNoActiveStyleMutation()
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        activeStyleMutation = reservation
      }
    }
    try {
      if (binding.sourceExists(id) == false) return false
      binding.removeSource(id)
      lifecycle.serialized {
        requireStyleHandleLocked(binding)
        imperativeSources.remove(id)
        binding.identity.sources.remove(id)
      }
      refreshSourcesAfterCommand(binding)
      return true
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not remove source '$id': ${error.message}", error)
    } finally {
      lifecycle.serialized { completeStyleMutation(reservation) }
    }
  }

  override fun addStyleImage(
    id: String,
    image: ImageBitmap,
    sdf: Boolean,
    stretch: ImageStretch?,
    expectedStyle: StyleBinding?,
  ): StyleImageHandle {
    val record = ImperativeImageRecord(fromResolver = false)
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      expectedStyle?.let(::requireStyleHandleLocked)
      requireNoDesiredImage(id)
      requireNoActiveStyleMutation()
      if (id in imperativeImages) {
        throw StyleHandleException("Image ID '$id' already exists in style")
      }
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        imperativeImages[id] = record
        activeStyleMutation = reservation
      }
    }
    var committed = false
    try {
      if (binding.imageExists(id) == true) {
        throw StyleHandleException("Image ID '$id' already exists in style")
      }
      binding.identity.images.remove(id)
      binding.addImage(id, image, sdf, stretch)
      val handle = lifecycle.serialized {
        requireStyleHandleLocked(binding)
        StyleImageHandleImpl(id, style, binding)
      }
      committed = true
      return handle
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not add image '$id': ${error.message}", error)
    } finally {
      lifecycle.serialized {
        if (!committed && imperativeImages[id] === record) imperativeImages.remove(id)
        completeStyleMutation(reservation)
      }
    }
  }

  /**
   * Starts resolution of a missing style image and returns the resolution, or null when no resolver
   * is set, no style is loaded, or this state no longer accepts [adapter].
   *
   * A repeated request for an id already resolving returns that resolution, which the browser needs
   * to keep the request pending while the first call runs.
   *
   * The engine reports the miss from its own thread, so the resolution runs on this state's scope
   * rather than there.
   */
  internal fun resolveMissingImage(adapter: MapAdapter, imageId: String): Deferred<Unit>? =
    lifecycle.serialized {
      if (lifecycle.isClosed || !lifecycle.acceptsAdapter(adapter)) return@serialized null
      val resolver = missingImageResolverState ?: return@serialized null
      val binding = style.currentLoadedStyle() ?: return@serialized null
      if (hasDesiredImage(imageId) || imperativeImages[imageId]?.fromResolver == false)
        return@serialized null
      missingImageResolutions[imageId]?.let {
        return@serialized it.work
      }
      val token = Any()
      runtime.physicalScope
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
      // Consult the engine outside the lifecycle lock: Native marshals this read to its map thread.
      if (binding.imageExists(imageId) == true) return
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
      addResolvedStyleImage(binding, imageId, resolved)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      runtime.logger?.w(error) { "Could not add the resolved image '$imageId'" }
    } finally {
      // Keep negative results to avoid a request loop, but let a later engine miss restore an
      // evicted image. An older resolver must not clear a replacement resolver's pending work.
      if (!rememberFailure) {
        lifecycle.serialized {
          if (missingImageResolutions[imageId]?.token === token)
            missingImageResolutions.remove(imageId)
        }
      }
    }
  }

  /**
   * Adds a resolved image to [binding].
   *
   * The engine asks as soon as it lays out a tile, so this waits out a style command in progress
   * rather than failing on it, and abandons the add once [binding] is no longer the loaded style.
   * The style that it adds to is not ready yet: the browser counts a style as loaded only once
   * every in-view tile has parsed, and a tile does not finish parsing until this add answers it.
   */
  private suspend fun addResolvedStyleImage(
    binding: StyleBinding,
    imageId: String,
    resolved: ResolvedStyleImage,
  ) {
    val record = ImperativeImageRecord(fromResolver = true)
    val reservation = StyleMutationReservation()
    var claimed = false
    while (true) {
      val inProgress = lifecycle.serialized {
        if (lifecycle.isClosed) return
        if (style.currentLoadedStyle() !== binding) return
        (activeStyleMutation ?: backgroundStyleMutation)?.let {
          return@serialized it
        }
        if (hasDesiredImage(imageId) || imperativeImages[imageId]?.fromResolver == false) return
        // Resolver ownership survives eviction, but must not replace an explicitly added image.
        if (imageId !in imperativeImages) {
          imperativeImages[imageId] = record
          claimed = true
        }
        backgroundStyleMutation = reservation
        null
      }
      if (inProgress == null) break
      inProgress.completion.await()
    }
    var committed = false
    try {
      if (binding.imageExists(imageId) == true) return
      // An engine eviction ends the previous image identity, even when its ID is reused.
      binding.identity.images.remove(imageId)
      binding.addImage(imageId, resolved.image, resolved.sdf, resolved.stretch)
      committed = lifecycle.serialized {
        !lifecycle.isClosed && style.isCurrentLoadedStyle(binding)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      // A style load can invalidate [binding] between the check above and these calls, which fails
      // the add for a style that nothing waits on any more.
      if (lifecycle.serialized { !lifecycle.isClosed && style.isCurrentLoadedStyle(binding) }) {
        if (error is StyleMutationException) {
          throw StyleHandleException("Could not add image '$imageId': ${error.message}", error)
        }
        throw error
      }
    } finally {
      lifecycle.serialized {
        if (claimed && !committed && imperativeImages[imageId] === record)
          imperativeImages.remove(imageId)
        completeStyleMutation(reservation)
      }
    }
  }

  /** Ends every resolution in flight: none of them can still reach the style that asked. */
  private fun cancelMissingImageResolutions() {
    missingImageResolutions.values.forEach { it.work.cancel() }
    missingImageResolutions.clear()
  }

  override fun removeStyleImage(id: String, expectedStyle: StyleBinding, identity: Any): Boolean {
    val reservation = StyleMutationReservation()
    val binding = lifecycle.serialized {
      requireOpenLocked()
      requireStyleHandleLocked(expectedStyle)
      check(expectedStyle.identity.images.isCurrent(id, identity)) {
        "Image '$id' has been removed or replaced"
      }
      requireNoDesiredImage(id)
      requireNoActiveStyleMutation()
      checkNotNull(style.currentLoadedStyle()).also(::requireStyleHandleLocked).also {
        activeStyleMutation = reservation
      }
    }
    try {
      if (binding.imageExists(id) == false) return false
      binding.removeImage(id)
      lifecycle.serialized {
        requireStyleHandleLocked(binding)
        imperativeImages.remove(id)
        binding.identity.images.remove(id)
      }
      return true
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not remove image '$id': ${error.message}", error)
    } finally {
      lifecycle.serialized { completeStyleMutation(reservation) }
    }
  }

  private fun refreshSourcesAfterCommand(binding: StyleBinding): Map<String, SourceHandle> {
    while (true) {
      val read = lifecycle.serialized {
        requireStyleHandleLocked(binding)
        StyleResourceRead(binding, styleHandleEpoch, ++styleSourceChangeRevision)
      }
      val sources = style.readSources(binding)
      val committed = lifecycle.serialized {
        requireStyleHandleLocked(binding)
        if (styleSourceChangeRevision != read.sourceChangeRevision) return@serialized false
        style.updateSources(sources)
        true
      }
      if (committed) return sources
    }
  }

  private fun requireNoDesiredSource(id: String) {
    if (desiredStyleRevision.sources.any { it.id == id }) {
      throw StyleHandleException("Source ID '$id' is declared by the style content")
    }
  }

  private fun hasDesiredImage(id: String): Boolean = desiredStyleRevision.images.any { it.id == id }

  private fun requireNoDesiredImage(id: String) {
    if (hasDesiredImage(id)) {
      throw StyleHandleException("Image ID '$id' is declared by the style content")
    }
  }

  private fun requireNoImperativeResourceConflicts(revision: DesiredStyleRevision) {
    revision.sources
      .firstOrNull { it.id in imperativeSources }
      ?.let {
        throw StyleHandleException("Source ID '${it.id}' is owned by an imperative addition")
      }
    revision.images
      .firstOrNull { it.id in imperativeImages }
      ?.let {
        throw StyleHandleException("Image ID '${it.id}' is owned by an imperative addition")
      }
  }

  private fun requireNoActiveStyleMutation() {
    if (activeStyleMutation != null) {
      throw StyleHandleException("Another imperative style resource command is in progress")
    }
  }

  private fun completeStyleMutation(reservation: StyleMutationReservation) {
    if (activeStyleMutation === reservation) activeStyleMutation = null
    if (backgroundStyleMutation === reservation) backgroundStyleMutation = null
    reservation.completion.complete(Unit)
  }

  override fun <T> runStyleHandleOperation(
    binding: StyleBinding,
    action: () -> T,
  ): T {
    lifecycle.serialized { requireStyleHandleLocked(binding) }
    val result = action()
    lifecycle.serialized { requireStyleHandleLocked(binding) }
    return result
  }

  private fun requireStyleHandleLocked(binding: StyleBinding) {
    requireOpenLocked()
    check(style.loadState == StyleLoadState.Ready && style.isCurrentLoadedStyle(binding)) {
      "Style operation belongs to a stale or unready loaded-style identity"
    }
  }

  /** Invalidates the loaded style when the map closes. */
  internal fun invalidateForClose() {
    styleHandleEpoch++
    cancelMissingImageResolutions()
    style.invalidateLoadedStyle()
  }

  /** Invalidates the loaded style of an adapter that closed under the current presentation. */
  internal fun invalidateClosedAdapter() {
    styleHandleEpoch++
    style.invalidateLoadedStyle()
    style.loadState = StyleLoadState.Pending
  }

  internal fun beginStyleLoadForNewAdapter() {
    styleHandleEpoch++
    style.invalidateLoadedStyle()
    style.loadState = StyleLoadState.Loading
  }

  /** Sends the durable base style to [adapter] while it awaits publication. */
  internal fun configurePendingAdapter(adapter: MapAdapter) {
    val command = lifecycle.serialized {
      if (!lifecycle.isPendingPublication(adapter)) return
      BaseStyleCommand(adapter, style.baseStyle, baseStyleCommandRevision)
    }
    applyBaseStyleCommand(command)
  }

  private fun applyBaseStyleCommand(initial: BaseStyleCommand) {
    var command = initial
    while (true) {
      if (lifecycle.currentAdapter() !== command.adapter) return
      command.adapter.setBaseStyle(command.value)
      command = lifecycle.serialized {
        if (lifecycle.currentAdapter() !== command.adapter) return
        if (baseStyleCommandRevision == command.revision) return
        BaseStyleCommand(command.adapter, style.baseStyle, baseStyleCommandRevision)
      }
    }
  }

  private fun requireOpenLocked() {
    check(!lifecycle.isClosed) { "The map state is closed" }
  }

  private data class BaseStyleCommand(
    val adapter: MapAdapter,
    val value: BaseStyle,
    val revision: Long,
  )

  private data class StyleResourceRead(
    val binding: StyleBinding,
    val styleHandleEpoch: Long,
    val sourceChangeRevision: Long,
  )
}
