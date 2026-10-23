package org.maplibre.compose.map

import org.maplibre.compose.sources.MutableSourceHandle
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleContents
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleImageDefinition
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.onRejected

/**
 * Main-confined ownership of imperative resources. The engine runs submitted writes in order; a
 * write it rejects restores the ownership and contents that the write had changed.
 */
internal class StyleResourceCommands(private val style: MapStyleState) {
  private class OwnedSource(val binding: StyleBinding, val definition: SourceDefinition)

  private val sources = mutableMapOf<String, OwnedSource>()
  // False means explicit ownership; true means the missing-image resolver supplied the image.
  private val images = mutableMapOf<String, Boolean>()
  // Sources whose removal the engine refused while an addition with the same ID was pending.
  private val retainedSources =
    mutableMapOf<String, Pair<OwnedSource?, StyleContents.SourceEntry>>()

  /** Image writes whose outcome is pending, with what to restore if the engine rejects them all. */
  /**
   * Overlapping writes to one image, and the state from before the first. When the latest write is
   * rejected, the image returns to what the latest accepted write produced, or to the earlier state
   * if none was accepted.
   */
  private class PendingImageWrites(val identity: Any?, val ownership: Boolean?) {
    var submitted = 0
    var completed = 0
    var latestAccepted = -1
    var latestAcceptedOwnership: Boolean? = null
    var latestError: Throwable? = null
  }

  private val pendingImageWrites = mutableMapOf<String, PendingImageWrites>()

  fun clear() {
    sources.clear()
    images.clear()
    retainedSources.clear()
    pendingImageWrites.clear()
  }

  fun sourceDefinition(id: String): SourceDefinition? = currentSource(id)?.definition

  fun sourceIds(): Set<String> =
    sources.filterValues { style.isCurrentLoadedStyle(it.binding) }.keys.toSet()

  private fun currentSource(id: String): OwnedSource? =
    sources[id]?.takeIf { style.isCurrentLoadedStyle(it.binding) }

  fun isExplicitImage(id: String): Boolean = images[id] == false

  fun requireNoConflicts(snapshot: StyleSnapshot) {
    snapshot.sources
      .firstOrNull { currentSource(it.id) != null }
      ?.let {
        error("Source ID '${it.id}' is owned by an imperative addition")
      }
    snapshot.images
      .firstOrNull { images[it.id] == false }
      ?.let {
        error("Image ID '${it.id}' is owned by an imperative addition")
      }
  }

  /** Submits the addition and returns its handle once the engine has added it. */
  suspend fun add(source: Source): MutableSourceHandle? {
    requireSourceWritable(source.id)
    val binding = readyBinding("add source '${source.id}'") ?: return null
    check(source.id !in binding.contents.sources) {
      "Source ID '${source.id}' already exists in style"
    }
    val definition = source.definition()
    val owned = OwnedSource(binding, definition)
    val command = binding.addSource(definition)
    sources[source.id] = owned
    binding.contents.addSource(definition)
    style.publish(binding)
    command.onRejected {
      if (sources[source.id] !== owned) return@onRejected
      sources.remove(source.id)
      binding.contents.removeSource(source.id)
      binding.identity.sources.remove(source.id)
      // A refused removal of the previous source with this ID left it in the engine.
      retainedSources.remove(source.id)?.let { (retained, entry) ->
        if (retained != null) sources[source.id] = retained
        binding.contents.restoreSource(source.id, entry)
      }
      if (style.isCurrentLoadedStyle(binding)) style.publish(binding)
    }
    try {
      command.await()
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not add source '${source.id}': ${error.message}", error)
    }
    if (!style.owner.isCurrent(binding) || sources[source.id] !== owned) return null
    return style.sourceHandle(source.id)?.asMutable
  }

  fun removeSource(id: String, binding: StyleBinding, identity: Any, onRemoved: () -> Unit) {
    requireSourceWritable(id)
    if (!style.owner.isCurrent(binding) || !binding.identity.sources.isCurrent(id, identity)) return
    val owned = sources.remove(id)
    val entry = binding.contents.removeSource(id)
    binding.identity.sources.remove(id)
    style.publish(binding)
    onRemoved()
    binding.removeSource(id).onRejected { error ->
      binding.reportRejectedWrite("Source '$id'", null, error)
      // A refused removal, such as for a source a layer still uses, keeps the source, which gets
      // its ownership back and a new handle. A pending addition with the ID will be rejected, and
      // the source comes back then.
      if (!style.isCurrentLoadedStyle(binding) || entry == null) return@onRejected
      if (id in binding.contents.sources) {
        retainedSources[id] = owned to entry
        return@onRejected
      }
      if (owned != null) sources[id] = owned
      binding.contents.restoreSource(id, entry)
      style.publish(binding)
    }
  }

  fun set(images: Map<String, ResolvedStyleImage>) {
    images.keys.forEach(::requireImageWritable)
    val binding = readyBinding("set style images") ?: return
    images.forEach { (id, image) ->
      writeImage(binding, id, StyleImageDefinition(id, image.image, image.sdf, image.stretch))
    }
  }

  fun removeImage(id: String) {
    requireImageWritable(id)
    val binding = readyBinding("remove image '$id'") ?: return
    writeImage(binding, id, null)
  }

  fun removeImage(id: String, binding: StyleBinding, identity: Any, onRemoved: () -> Unit) {
    requireImageWritable(id)
    if (!style.owner.isCurrent(binding) || !binding.identity.images.isCurrent(id, identity)) return
    writeImage(binding, id, null)
    onRemoved()
  }

  suspend fun image(id: String): StyleImageHandle? {
    style.owner.requireOpen()
    val binding = style.readyLoadedStyle() ?: return null
    val exists = style.operationGuard(binding).read({ true }) { binding.imageExists(id) } == true
    return if (exists && style.owner.isCurrent(binding)) StyleImageHandleImpl(id, style, binding)
    else null
  }

  /** Missing images can be supplied before the style is ready. */
  fun supply(binding: StyleBinding, id: String, image: ResolvedStyleImage) {
    if (
      !style.isCurrentLoadedStyle(binding) ||
        !binding.isLoaded ||
        !style.isImageWritable(id) ||
        isExplicitImage(id)
    )
      return
    binding.setImage(StyleImageDefinition(id, image.image, image.sdf, image.stretch)).onRejected {
      error ->
      binding.reportRejectedWrite("Image '$id'", null, error)
    }
    images[id] = true
    binding.identity.images.remove(id)
  }

  /**
   * Writes or removes an image and expires its handles. The engine keeps its previous image when it
   * rejects the write. When it rejects every write submitted since the image was last settled, the
   * image gets back the handles and ownership it had before them.
   */
  private fun writeImage(binding: StyleBinding, id: String, definition: StyleImageDefinition?) {
    val command = if (definition == null) binding.removeImage(id) else binding.setImage(definition)
    val pending =
      pendingImageWrites.getOrPut(id) {
        PendingImageWrites(binding.identity.images.peek(id), images[id])
      }
    val write = pending.submitted++
    val ownership = if (definition == null) null else false
    binding.identity.images.take(id)
    if (ownership == null) images.remove(id) else images[id] = ownership
    command.invokeOnCompletion { error ->
      if (error == null && write > pending.latestAccepted) {
        pending.latestAccepted = write
        pending.latestAcceptedOwnership = ownership
      }
      if (write == pending.submitted - 1) pending.latestError = error
      if (error is StyleMutationException) binding.reportRejectedWrite("Image '$id'", null, error)
      if (++pending.completed < pending.submitted || pendingImageWrites[id] !== pending)
        return@invokeOnCompletion
      pendingImageWrites.remove(id)
      if (pending.latestError !is StyleMutationException) return@invokeOnCompletion
      if (!style.isCurrentLoadedStyle(binding)) return@invokeOnCompletion
      val restored =
        if (pending.latestAccepted >= 0) pending.latestAcceptedOwnership else pending.ownership
      if (restored == null) images.remove(id) else images[id] = restored
      // An accepted write replaced the image, so only a group with none keeps the earlier handle.
      if (pending.latestAccepted < 0)
        pending.identity?.let { binding.identity.images.restore(id, it) }
    }
  }

  private fun readyBinding(target: String): StyleBinding? {
    style.owner.requireOpen()
    val binding = style.readyLoadedStyle()
    if (binding == null)
      style.owner.logger?.w { "Skipped the command to $target: no style is ready" }
    return binding
  }

  private fun requireSourceWritable(id: String) {
    require(id.isNotBlank()) { "Source ID must not be blank" }
    style.requireSourceWritable(id)
  }

  private fun requireImageWritable(id: String) {
    require(id.isNotBlank()) { "Image ID must not be blank" }
    style.requireImageWritable(id)
  }
}
