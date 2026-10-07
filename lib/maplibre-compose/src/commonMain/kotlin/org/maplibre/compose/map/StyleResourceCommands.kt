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

/**
 * One ordered commit boundary for declarations, resource commands, and their published handles.
 *
 * A command reaches the engine in one [StyleBinding.awaitOwner] task, so the caller's thread and
 * the thread running the command never wait on the map owner. [commitSources] runs a source
 * mutation in the same task as the metadata read for publication.
 */
internal class StyleResourceCommands(
  private val style: MapStyleState,
  private val scope: CoroutineScope,
  /** Returns false, with nothing published, when the loaded style changed first. */
  private val commitSources: suspend (StyleBinding, mutate: () -> Unit) -> Boolean,
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
      ?.let { error("Source ID '${it.id}' is owned by an imperative addition") }
    // An image the missing-image resolver supplied is not a claim: its ID can match a generated
    // one.
    snapshot.images
      .firstOrNull { images[it.id] == false }
      ?.let { error("Image ID '${it.id}' is owned by an imperative addition") }
  }

  /**
   * Admits the addition in call order, then suspends until it has run. A rejection reaches the
   * caller instead of the log.
   *
   * @return null, after logging, when no style is ready or the loaded style changes before the
   *   handle is published.
   */
  suspend fun add(source: Source): MutableSourceHandle? {
    val target = "add source '${source.id}'"
    requireSourceWritable(source.id)
    style.owner.requireOpen()
    val definition = source.definition()
    val completion = CompletableDeferred<Unit>()
    var ran = false
    var addedTo: StyleBinding? = null
    submitToReadyStyle(target, onSkipped = { completion.complete(Unit) }) { binding ->
      launchCommand(binding, target, completion = completion) {
        ran = true
        requireSourceWritable(source.id)
        // Record before the owner task so metadata capture can use this definition. A failed
        // addition takes the record back.
        lock.withLock {
          check(currentSource(source.id) == null) { "Source ID '${source.id}' already exists" }
          sources[source.id] = OwnedSource(binding, definition)
        }
        var inserted = false
        var added = false
        try {
          val committed =
            commitSources(binding) {
              check(binding.sourceExists(source.id) != true) {
                "Source ID '${source.id}' already exists"
              }
              // False means the style unloaded first, and nothing was added.
              inserted = binding.addSource(definition)
            }
          added = committed && inserted
        } finally {
          if (added) addedTo = binding else forgetSource(source.id, binding)
        }
      }
    }
    completion.await()
    val handle =
      addedTo
        ?.takeIf { style.owner.isCurrent(it) }
        ?.let { style.sourceHandle(source.id) }
        ?.asMutable
    // A command that never ran has logged that already.
    if (handle == null && ran) skipped(target, StyleChanged)
    return handle
  }

  /** The handle checked [identity] before this call; the command checks it again when it runs. */
  /** [onRemoved] runs once this command has removed the source. */
  fun removeSource(id: String, binding: StyleBinding, identity: Any, onRemoved: () -> Unit) {
    val target = "remove source '$id'"
    style.owner.runInOrder {
      launchCommand(binding, target) {
        requireSourceWritable(id)
        if (!binding.identity.sources.isCurrent(id, identity)) {
          skipped(target, ResourceChanged)
          return@launchCommand
        }
        val committed =
          commitSources(binding) {
            // Null means the engine cannot tell; attempt removal rather than untrack a live
            // source.
            if (binding.sourceExists(id) != false) binding.removeSource(id)
            if (style.isCurrentLoadedStyle(binding)) {
              forgetSource(id, binding)
              binding.identity.sources.remove(id)
            }
          }
        if (committed) onRemoved() else skipped(target, StyleChanged)
      }
    }
  }

  fun set(images: Map<String, ResolvedStyleImage>) {
    images.keys.forEach(::requireImageWritable)
    style.owner.requireOpen()
    val target = "set style images"
    // Snapshot the caller's collection; prepared images already own their immutable pixels.
    val definitions = images.mapValues { (id, image) ->
      StyleImageDefinition(id, image.image, image.sdf, image.stretch)
    }
    submitToReadyStyle(target) { binding ->
      val sequence = enqueueImageWrites(definitions)
      launchCommand(
        binding,
        target,
        discarded = { releaseImageWrites(definitions.keys, sequence) },
      ) {
        applyImageWrites(binding, takeImageWrites(definitions.keys, sequence))
      }
    }
  }

  fun removeImage(id: String) {
    requireImageWritable(id)
    style.owner.requireOpen()
    val target = "remove image '$id'"
    submitToReadyStyle(target) { binding ->
      val sequence = enqueueImageWrites(mapOf(id to null))
      launchCommand(binding, target, discarded = { releaseImageWrites(setOf(id), sequence) }) {
        applyImageWrites(binding, takeImageWrites(setOf(id), sequence))
      }
    }
  }

  /** The handle checked [identity] before this call; the command checks it again when it runs. */
  /**
   * [onRemoved] runs once this command has removed the image, not when a later write replaced it.
   */
  fun removeImage(id: String, binding: StyleBinding, identity: Any, onRemoved: () -> Unit) {
    val target = "remove image '$id'"
    style.owner.runInOrder {
      // A handle's removal is conditional on its identity, so it never discards an earlier write:
      // a pending replacement expires the handle first. It applies a later write in its place.
      val sequence = lock.withLock { ++imageSequence }
      launchCommand(binding, target) {
        val later = takeImageWrites(setOf(id), sequence)
        if (later.isEmpty()) {
          requireImageWritable(id)
          if (!binding.identity.images.isCurrent(id, identity)) {
            skipped(target, ResourceChanged)
            return@launchCommand
          }
        }
        val applied = applyImageWrites(binding, later.ifEmpty { mapOf(id to null) })
        if (later.isEmpty() && id in applied) onRemoved()
      }
    }
  }

  suspend fun image(id: String): StyleImageHandle? {
    style.owner.requireOpen()
    return style.owner.readInOrder {
      val binding = style.readyLoadedStyle() ?: return@readInOrder null
      withCommit {
        val exists = style.visit(binding) { binding.imageExists(id) == true } == true
        if (exists) StyleImageHandleImpl(id, style, binding) else null
      }
    }
  }

  /** A missing image may be needed before the loaded style can become ready. */
  suspend fun supply(binding: StyleBinding, id: String, image: ResolvedStyleImage) = withCommit {
    if (!style.isCurrentLoadedStyle(binding) || !style.isImageWritable(id) || isExplicitImage(id))
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
  /** @return the IDs whose writes the engine applied. */
  private suspend fun applyImageWrites(
    binding: StyleBinding,
    writes: Map<String, StyleImageDefinition?>,
  ): Set<String> {
    if (writes.isEmpty()) return emptySet()
    writes.keys.forEach(::requireImageWritable)
    val definitions = writes.values.filterNotNull()
    val removals = writes.filterValues { it == null }.keys
    // Each write fails on its own; a style that unloads during the batch drops all of it.
    val results =
      style.visit(binding) {
        definitions.map { runCatching { binding.setImage(it) } } +
          removals.map { runCatching<Unit> { binding.removeImage(it) } }
      }
    if (results == null) {
      skipped("write style images ${writes.keys}", StyleChanged)
      return emptySet()
    }
    if (!style.isCurrentLoadedStyle(binding)) return emptySet()
    val applied = mutableSetOf<String>()
    (definitions.map { it.id } + removals).zip(results).forEach { (id, result) ->
      result.fold(
        onSuccess = {
          lock.withLock {
            if (id in removals) images.remove(id) else images[id] = false
          }
          binding.identity.images.remove(id)
          applied += id
        },
        onFailure = { error ->
          if (error !is Exception) throw error
          rejected("${if (id in removals) "remove" else "set"} image '$id'", error)
        },
      )
    }
    return applied
  }

  /**
   * [discarded] runs instead of [block] when the command is dropped or its scope is cancelled; a
   * command dropped because the loaded style changed is logged. A [completion] receives the outcome
   * for a caller that waits: it completes normally when the command is dropped, and a failure goes
   * to it rather than to [rejected]. Callers start it in the command's turn (see
   * [MapStyleStateOwner.runInOrder]).
   */
  private fun launchCommand(
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
          if (!style.owner.isCurrent(binding)) {
            skipped(target, StyleChanged)
            return@withCommit
          }
          // Start work through the owner's dispatcher: UNDISPATCHED admission may still be on an
          // arbitrary caller, and withContext alone would run the command inline there.
          withContext(NonCancellable) {
            async {
              try {
                if (!style.owner.isCurrent(binding)) {
                  skipped(target, StyleChanged)
                  return@async
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
          completion?.complete(Unit)
        }
      }
    }
  }

  /**
   * Starts a command, in its turn (see [MapStyleStateOwner.runInOrder]), on the style that is ready
   * then. Without one, it logs that [target] was skipped and runs [onSkipped] instead.
   */
  private fun submitToReadyStyle(
    target: String,
    onSkipped: () -> Unit = {},
    start: (StyleBinding) -> Unit,
  ) {
    style.owner.runInOrder {
      val binding = style.readyLoadedStyle()
      if (binding != null) {
        start(binding)
      } else {
        skipped(target, "no style is ready")
        onSkipped()
      }
    }
  }

  private fun skipped(target: String, reason: String) {
    style.owner.logger?.w { "Skipped the command to $target: $reason" }
  }

  private fun rejected(target: String, error: Throwable) {
    style.owner.logger?.w(error) { "Could not $target" }
  }

  private fun requireSourceWritable(id: String) {
    require(id.isNotBlank()) { "Source ID must not be blank" }
    style.requireSourceWritable(id)
  }

  private fun requireImageWritable(id: String) {
    require(id.isNotBlank()) { "Image ID must not be blank" }
    style.requireImageWritable(id)
  }

  private companion object {
    const val StyleChanged = "the loaded style changed first"
    const val ResourceChanged = "the resource was removed or replaced first"
  }
}
