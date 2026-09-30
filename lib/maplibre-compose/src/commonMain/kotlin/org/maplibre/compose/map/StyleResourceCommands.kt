package org.maplibre.compose.map

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.compose.sources.MutableSourceHandle
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleImageDefinition
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.checkStyleHandle

/**
 * One ordered commit boundary for declarations, resource commands, and their published handles.
 *
 * A command reaches the engine in one [StyleBinding.awaitOwner] task, so the caller's thread and
 * the thread running the command never wait on the map owner. [commitSources] runs a source
 * mutation in the same task as the source read that republishes the handles.
 */
internal class StyleResourceCommands(
  private val style: MapStyleState,
  private val scope: CoroutineScope,
  private val commitSources: suspend (StyleBinding, mutate: () -> Unit) -> Unit,
  private val rejected: (String, Throwable) -> Unit,
) {
  private val mutex = Mutex()
  private val lock = reentrantLock()

  // Each record names the loaded style it belongs to. clear() runs on a style change, but a
  // command's bookkeeping can land around it, and a stale record must not claim the ID in the
  // next style.
  private class OwnedSource(val binding: StyleBinding, val definition: SourceDefinition)

  private val sources = mutableMapOf<String, OwnedSource>()
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

  fun sourceDefinition(id: String): SourceDefinition? = lock.withLock {
    currentSource(id)?.definition
  }

  fun sourceIds(): Set<String> = lock.withLock {
    sources.filterValues { style.isCurrentLoadedStyle(it.binding) }.keys.toSet()
  }

  /** Call under [lock]. */
  private fun currentSource(id: String): OwnedSource? =
    sources[id]?.takeIf { style.isCurrentLoadedStyle(it.binding) }

  private fun forgetSource(id: String, binding: StyleBinding) = lock.withLock {
    if (sources[id]?.binding === binding) sources.remove(id)
  }

  fun isExplicitImage(id: String): Boolean = lock.withLock { images[id] == false }

  fun pendingImageWriteIds(): Set<String> = lock.withLock { pendingImageWrites.keys.toSet() }

  fun requireNoConflicts(snapshot: StyleSnapshot) = lock.withLock {
    snapshot.sources
      .firstOrNull { currentSource(it.id) != null }
      ?.let {
        throw StyleHandleException("Source ID '${it.id}' is owned by an imperative addition")
      }
    snapshot.images
      .firstOrNull { it.id in images }
      ?.let {
        throw StyleHandleException("Image ID '${it.id}' is owned by an imperative addition")
      }
  }

  /**
   * Admits the addition in call order, then suspends until it has run. A rejection reaches the
   * caller instead of the log.
   */
  suspend fun add(source: Source): MutableSourceHandle {
    val binding = requireBinding()
    requireSourceWritable(source.id)
    val definition = source.definition()
    val completion = CompletableDeferred<Unit>()
    submit(binding, "add source '${source.id}'", completion = completion) {
      requireSourceWritable(source.id)
      // Recorded on this thread, before the owner task, so the source read inside the task builds
      // the handle from this definition. A failed addition takes the record back.
      lock.withLock {
        checkStyleHandle(currentSource(source.id) == null) {
          "Source ID '${source.id}' already exists"
        }
        sources[source.id] = OwnedSource(binding, definition)
      }
      try {
        commitSources(binding) {
          checkStyleHandle(binding.sourceExists(source.id) != true) {
            "Source ID '${source.id}' already exists"
          }
          checkStyleHandle(binding.addSource(definition)) {
            "The loaded style changed during source insertion"
          }
        }
      } catch (error: Exception) {
        forgetSource(source.id, binding)
        throw error
      }
    }
    completion.await()
    return style.sourceHandle(source.id)?.takeIf { style.isCurrentLoadedStyle(binding) }?.asMutable
      ?: throw StyleHandleException(
        "Could not add source '${source.id}': the loaded style changed after it was added"
      )
  }

  fun removeSource(id: String, binding: StyleBinding, identity: Any) {
    validateSource(id, binding, identity)
    submit(binding, "remove source '$id'") {
      validateSource(id, binding, identity)
      commitSources(binding) {
        // Null means the engine cannot tell; attempt removal rather than untrack a live source.
        if (binding.sourceExists(id) != false) binding.removeSource(id)
        if (style.isCurrentLoadedStyle(binding)) {
          forgetSource(id, binding)
          binding.identity.sources.remove(id)
        }
      }
    }
  }

  fun set(images: Map<String, ResolvedStyleImage>) {
    val binding = requireBinding()
    images.keys.forEach(::requireImageWritable)
    // Snapshot the caller's collection; prepared images already own their immutable pixels.
    val definitions = images.mapValues { (id, image) ->
      StyleImageDefinition(id, image.image, image.sdf, image.stretch)
    }
    val sequence = enqueueImageWrites(definitions)
    submit(
      binding,
      "set style images",
      discarded = { releaseImageWrites(definitions.keys, sequence) },
    ) {
      applyImageWrites(binding, takeImageWrites(definitions.keys, sequence))
    }
  }

  fun removeImage(id: String, binding: StyleBinding = requireBinding(), identity: Any? = null) {
    if (identity == null) {
      requireImageWritable(id)
      val sequence = enqueueImageWrites(mapOf(id to null))
      submit(
        binding,
        "remove image '$id'",
        discarded = { releaseImageWrites(setOf(id), sequence) },
      ) {
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
      val exists = binding.awaitOwner { binding.imageExists(id) == true } == true
      if (exists && style.readyLoadedStyle() === binding) StyleImageHandleImpl(id, style, binding)
      else null
    }
  }

  /** A missing image may be needed before the loaded style can become ready. */
  suspend fun supply(binding: StyleBinding, id: String, image: ResolvedStyleImage) = withCommit {
    if (
      !style.isCurrentLoadedStyle(binding) ||
        !style.owner.isImageWritable(id) ||
        isExplicitImage(id)
    )
      return@withCommit
    val definition = StyleImageDefinition(id, image.image, image.sdf, image.stretch)
    withContext(NonCancellable) {
      // A queued miss may arrive after another request supplied the image, so the check and the
      // write share one owner task. An unloaded style has nothing left to supply.
      val supplied = binding.awaitOwner {
        if (binding.imageExists(id) != false) return@awaitOwner false
        binding.setImage(definition)
        true
      }
      if (supplied != true) return@withContext
      if (style.isCurrentLoadedStyle(binding)) {
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

  /** A command dropped before it runs releases its writes, so an abandoned binding retains none. */
  private fun releaseImageWrites(ids: Set<String>, sequence: Long) = lock.withLock {
    ids.forEach { id ->
      if (pendingImageWrites[id]?.sequence == sequence) pendingImageWrites.remove(id)
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
    val results = binding.onOwner {
      definitions.map { runCatching { binding.setImage(it) } } +
        removals.map { runCatching<Unit> { binding.removeImage(it) } }
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

  /**
   * [discarded] runs instead of [block] when the command is dropped or its scope is cancelled. A
   * [completion] receives the outcome for a caller that waits: a failure then goes to it rather
   * than to [rejected].
   */
  private fun submit(
    binding: StyleBinding,
    target: String,
    discarded: () -> Unit = {},
    completion: CompletableDeferred<Unit>? = null,
    block: suspend () -> Unit,
  ) {
    // Enter the mutex before returning so separately submitted commands preserve admission order.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      var started = false
      try {
        withCommit {
          if (!style.isCurrentLoadedStyle(binding)) return@withCommit
          // Start work through the owner's dispatcher: UNDISPATCHED admission may still be on an
          // arbitrary caller, and withContext alone would run the command inline there.
          withContext(NonCancellable) {
            async {
              try {
                checkStyleHandle(style.readyLoadedStyle() === binding) {
                  "Style command belongs to an unready loaded-style identity"
                }
                started = true
                block()
                completion?.complete(Unit)
              } catch (error: Exception) {
                if (completion != null) {
                  completion.completeExceptionally(
                    if (error is StyleMutationException)
                      StyleHandleException("Could not $target: ${error.message}", error)
                    else error
                  )
                } else if (error !is CancellationException && style.isCurrentLoadedStyle(binding)) {
                  rejected(target, error)
                }
                if (error is CancellationException) throw error
              }
            }
              .await()
          }
        }
      } catch (error: CancellationException) {
        completion?.cancel(error)
        throw error
      } finally {
        if (!started) {
          discarded()
          completion?.completeExceptionally(
            StyleHandleException("Could not $target: the loaded style changed first")
          )
        }
      }
    }
  }

  /** Runs [action] on the map owner; a dropped task fails the command. */
  private suspend fun <T> StyleBinding.onOwner(action: () -> T): T =
    awaitOwner(action)
      ?: throw StyleHandleException("The loaded style changed before the command ran")

  private fun requireBinding(): StyleBinding =
    (style.readyLoadedStyle() ?: throw StyleHandleException("No ready loaded style")).also {
      style.operationGuard(it).run {}
    }

  private fun requireSourceWritable(id: String) {
    require(id.isNotBlank()) { "Source ID must not be blank" }
    style.owner.requireSourceWritable(id)
  }

  private fun requireImageWritable(id: String) {
    require(id.isNotBlank()) { "Image ID must not be blank" }
    if (!style.owner.isImageWritable(id))
      throw StyleHandleException("Image ID '$id' is declared by the style content")
  }

  private fun validateSource(id: String, binding: StyleBinding, identity: Any) =
    style.operationGuard(binding).run {
      requireSourceWritable(id)
      checkStyleHandle(binding.identity.sources.isCurrent(id, identity)) {
        "Source '$id' has been removed or replaced"
      }
    }

  private fun validateImage(id: String, binding: StyleBinding, identity: Any) =
    style.operationGuard(binding).run {
      requireImageWritable(id)
      checkStyleHandle(binding.identity.images.isCurrent(id, identity)) {
        "Image '$id' has been removed or replaced"
      }
    }
}
