package org.maplibre.compose.map

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleImageDefinition
import org.maplibre.compose.style.StyleSnapshot

/** One ordered commit boundary for declarations, resource commands, and their published handles. */
internal class StyleResourceCommands(
  private val style: MapStyleState,
  private val scope: CoroutineScope,
  private val readDispatcher: CoroutineDispatcher,
  private val refreshSources: suspend (StyleBinding) -> Unit,
  private val rejected: (String, Throwable) -> Unit,
) {
  private val mutex = Mutex()
  private val lock = reentrantLock()
  private val sources = mutableMapOf<String, SourceDefinition>()
  // False means explicit ownership; true means a missing-image resolver supplied the image.
  private val images = mutableMapOf<String, Boolean>()
  // Submission sequence of the newest unconditional set or remove per image ID. An older pending
  // command for that ID skips its write, like a superseded GeoJSON update.
  private var imageSequence = 0L
  private val latestImageCommands = mutableMapOf<String, Long>()

  suspend fun <T> withCommit(block: suspend () -> T): T = mutex.withLock { block() }

  suspend fun await() = withCommit {}

  fun clear() = lock.withLock {
    sources.clear()
    images.clear()
    latestImageCommands.clear()
  }

  fun sourceDefinition(id: String): SourceDefinition? = lock.withLock { sources[id] }

  fun sourceIds(): Set<String> = lock.withLock { sources.keys.toSet() }

  fun isExplicitImage(id: String): Boolean = lock.withLock { images[id] == false }

  fun requireNoConflicts(snapshot: StyleSnapshot) = lock.withLock {
    snapshot.sources
      .firstOrNull { it.id in sources }
      ?.let {
        throw StyleHandleException("Source ID '${it.id}' is owned by an imperative addition")
      }
    snapshot.images
      .firstOrNull { it.id in images }
      ?.let {
        throw StyleHandleException("Image ID '${it.id}' is owned by an imperative addition")
      }
  }

  fun add(source: Source) {
    val binding = requireBinding()
    requireSourceWritable(source.id)
    val definition = source.definition()
    submit(binding, "add source '${source.id}'") {
      requireSourceWritable(source.id)
      withContext(readDispatcher) {
        check(binding.sourceExists(source.id) != true) { "Source ID '${source.id}' already exists" }
        check(binding.addSource(definition)) { "The loaded style changed during source insertion" }
      }
      if (style.isCurrentLoadedStyle(binding)) {
        lock.withLock { sources[source.id] = definition }
        refreshSources(binding)
      }
    }
  }

  fun removeSource(id: String, binding: StyleBinding, identity: Any) {
    validateSource(id, binding, identity)
    submit(binding, "remove source '$id'") {
      validateSource(id, binding, identity)
      withContext(readDispatcher) {
        if (binding.sourceExists(id) == true) binding.removeSource(id)
      }
      if (style.isCurrentLoadedStyle(binding)) {
        lock.withLock { sources.remove(id) }
        binding.identity.sources.remove(id)
        refreshSources(binding)
      }
    }
  }

  fun set(images: Map<String, ResolvedStyleImage>) {
    val binding = requireBinding()
    images.keys.forEach(::requireImageWritable)
    // Snapshot the caller's collection; prepared images already own their immutable pixels.
    val definitions = images.map { (id, image) ->
      StyleImageDefinition(id, image.pixels, image.sdf, image.stretch)
    }
    val sequence = supersedeImages(images.keys)
    submit(binding, "set style images") {
      val current = definitions.filter { isLatestImageCommand(it.id, sequence) }
      if (current.isEmpty()) return@submit
      current.forEach { requireImageWritable(it.id) }
      val results = withContext(readDispatcher) { binding.setImages(current) }
      if (style.isCurrentLoadedStyle(binding))
        current.zip(results).forEach { (definition, result) ->
          result.fold(
            onSuccess = {
              lock.withLock { this@StyleResourceCommands.images[definition.id] = false }
              binding.identity.images.remove(definition.id)
            },
            onFailure = { error ->
              if (error !is Exception) throw error
              rejected("set image '${definition.id}'", error)
            },
          )
        }
    }
  }

  fun removeImage(id: String, binding: StyleBinding = requireBinding(), identity: Any? = null) {
    if (identity != null) validateImage(id, binding, identity) else requireImageWritable(id)
    // A handle's removal is conditional on its identity, so it yields to later commands but does
    // not supersede earlier ones: a pending replacement would expire the handle first.
    val sequence =
      if (identity != null) lock.withLock { ++imageSequence } else supersedeImages(setOf(id))
    submit(binding, "remove image '$id'") {
      if (!isLatestImageCommand(id, sequence)) return@submit
      if (identity != null) validateImage(id, binding, identity) else requireImageWritable(id)
      withContext(readDispatcher) { binding.removeImage(id) }
      if (style.isCurrentLoadedStyle(binding)) {
        lock.withLock { images.remove(id) }
        binding.identity.images.remove(id)
      }
    }
  }

  suspend fun image(id: String): StyleImageHandle? {
    val binding = style.readyLoadedStyle() ?: return null
    return withCommit {
      if (style.readyLoadedStyle() !== binding) return@withCommit null
      val exists = withContext(readDispatcher) { binding.imageExists(id) == true }
      if (exists && style.readyLoadedStyle() === binding) StyleImageHandleImpl(id, style, binding)
      else null
    }
  }

  /** A missing image may be needed before the loaded style can become ready. */
  suspend fun supply(binding: StyleBinding, id: String, image: ResolvedStyleImage) = withCommit {
    if (
      !style.isCurrentLoadedStyle(binding) ||
        !style.requireOwner().isImageWritable(id) ||
        isExplicitImage(id)
    )
      return@withCommit
    withContext(NonCancellable) {
      val added =
        withContext(readDispatcher) {
          if (binding.imageExists(id) == true) false
          else {
            binding.setImage(StyleImageDefinition(id, image.pixels, image.sdf, image.stretch))
            true
          }
        }
      if (added && style.isCurrentLoadedStyle(binding)) {
        lock.withLock { images[id] = true }
        binding.identity.images.remove(id)
      }
    }
  }

  private fun supersedeImages(ids: Set<String>): Long = lock.withLock {
    val sequence = ++imageSequence
    ids.forEach { latestImageCommands[it] = sequence }
    sequence
  }

  /**
   * Commands run in submission order, so an entry no newer than [sequence] has already run or been
   * dropped; releasing it keeps the map to pending IDs.
   */
  private fun isLatestImageCommand(id: String, sequence: Long): Boolean = lock.withLock {
    val latest = latestImageCommands[id] ?: return@withLock true
    (latest <= sequence).also { if (it) latestImageCommands.remove(id) }
  }

  private fun submit(binding: StyleBinding, target: String, block: suspend () -> Unit) {
    // Enter the mutex before returning so separately submitted commands preserve admission order.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      withCommit {
        if (!style.isCurrentLoadedStyle(binding)) return@withCommit
        // Start work through the owner's dispatcher: UNDISPATCHED admission may still be on an
        // arbitrary caller. In particular, a snapshot's scope names its read dispatcher even when
        // admission is running on the UI thread. withContext alone would then run inline.
        withContext(NonCancellable) {
          async {
            try {
              check(style.readyLoadedStyle() === binding) {
                "Style command belongs to an unready loaded-style identity"
              }
              block()
            } catch (error: Exception) {
              if (style.isCurrentLoadedStyle(binding)) rejected(target, error)
            }
          }
            .await()
        }
      }
    }
  }

  private fun requireBinding(): StyleBinding =
    checkNotNull(style.readyLoadedStyle()) { "No ready loaded style" }
      .also {
        style.operationGuard(it).run {}
      }

  private fun requireSourceWritable(id: String) {
    require(id.isNotBlank()) { "Source ID must not be blank" }
    style.requireOwner().requireSourceWritable(id)
  }

  private fun requireImageWritable(id: String) {
    require(id.isNotBlank()) { "Image ID must not be blank" }
    if (!style.requireOwner().isImageWritable(id))
      throw StyleHandleException("Image ID '$id' is declared by the style content")
  }

  private fun validateSource(id: String, binding: StyleBinding, identity: Any) =
    style.operationGuard(binding).run {
      requireSourceWritable(id)
      check(binding.identity.sources.isCurrent(id, identity)) {
        "Source '$id' has been removed or replaced"
      }
    }

  private fun validateImage(id: String, binding: StyleBinding, identity: Any) =
    style.operationGuard(binding).run {
      requireImageWritable(id)
      check(binding.identity.images.isCurrent(id, identity)) {
        "Image '$id' has been removed or replaced"
      }
    }
}
