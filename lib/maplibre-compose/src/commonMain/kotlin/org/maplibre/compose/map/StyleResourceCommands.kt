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

  // The newest unconditional write per image ID that no command has applied yet; null removes. The
  // first queued command for the ID applies it, so a state change queued behind that command cannot
  // strand the write, and later commands for the ID find nothing left to do.
  private class PendingImageWrite(val sequence: Long, val definition: StyleImageDefinition?)

  private var imageSequence = 0L
  private val pendingImageWrites = mutableMapOf<String, PendingImageWrite>()

  suspend fun <T> withCommit(block: suspend () -> T): T = mutex.withLock { block() }

  suspend fun await() = withCommit {}

  fun clear() = lock.withLock {
    sources.clear()
    images.clear()
    pendingImageWrites.clear()
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
    val sequence =
      enqueueImageWrites(
        images.mapValues { (id, image) ->
          StyleImageDefinition(id, image.pixels, image.sdf, image.stretch)
        }
      )
    submit(binding, "set style images") {
      applyImageWrites(binding, takeImageWrites(images.keys, sequence))
    }
  }

  fun removeImage(id: String, binding: StyleBinding = requireBinding(), identity: Any? = null) {
    if (identity == null) {
      requireImageWritable(id)
      val sequence = enqueueImageWrites(mapOf(id to null))
      submit(binding, "remove image '$id'") {
        applyImageWrites(binding, takeImageWrites(setOf(id), sequence))
      }
      return
    }
    validateImage(id, binding, identity)
    // A handle's removal is conditional on its identity, so it never discards an earlier write: a
    // pending replacement expires the handle first. It applies a later write in its place.
    val sequence = lock.withLock { ++imageSequence }
    submit(binding, "remove image '$id'") {
      val later = takeImageWrites(setOf(id), sequence)
      if (later.isEmpty()) validateImage(id, binding, identity)
      applyImageWrites(binding, later.ifEmpty { mapOf(id to null) })
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

  private fun enqueueImageWrites(writes: Map<String, StyleImageDefinition?>): Long = lock.withLock {
    val sequence = ++imageSequence
    writes.forEach { (id, definition) ->
      pendingImageWrites[id] = PendingImageWrite(sequence, definition)
    }
    sequence
  }

  /**
   * Takes the pending writes at least as new as [sequence]. Commands run in submission order, so an
   * older entry belongs to a command that was dropped; releasing it keeps the map to pending IDs.
   */
  private fun takeImageWrites(
    ids: Set<String>,
    sequence: Long,
  ): Map<String, StyleImageDefinition?> = lock.withLock {
    buildMap {
      ids.forEach { id ->
        val pending = pendingImageWrites.remove(id) ?: return@forEach
        if (pending.sequence >= sequence) put(id, pending.definition)
      }
    }
  }

  /** Each write succeeds or fails independently; a failed write keeps the previous image. */
  private suspend fun applyImageWrites(
    binding: StyleBinding,
    writes: Map<String, StyleImageDefinition?>,
  ) {
    if (writes.isEmpty()) return
    writes.keys.forEach(::requireImageWritable)
    val definitions = writes.values.filterNotNull()
    val removals = writes.filterValues { it == null }.keys
    val results =
      withContext(readDispatcher) {
        val set = if (definitions.isEmpty()) emptyList() else binding.setImages(definitions)
        set + removals.map { runCatching<Unit> { binding.removeImage(it) } }
      }
    if (!style.isCurrentLoadedStyle(binding)) return
    (definitions.map { it.id } + removals).zip(results).forEach { (id, result) ->
      result.fold(
        onSuccess = {
          lock.withLock {
            if (id in removals) images.remove(id) else images[id] = false
          }
          binding.identity.images.remove(id)
        },
        onFailure = { error ->
          if (error !is Exception) throw error
          rejected("${if (id in removals) "remove" else "set"} image '$id'", error)
        },
      )
    }
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
