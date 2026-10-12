package org.maplibre.compose.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.sources.MutableSourceHandle
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleImageDefinition
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.StyleSnapshot

/** Main-confined ownership of imperative resources; the engine orders submitted commands. */
internal class StyleResourceCommands(
  private val style: MapStyleState,
  private val scope: CoroutineScope,
  private val commitSources: suspend (StyleBinding) -> Boolean,
) {
  private class OwnedSource(val binding: StyleBinding, val definition: SourceDefinition)

  private val sources = mutableMapOf<String, OwnedSource>()
  // False means explicit ownership; true means the missing-image resolver supplied the image.
  private val images = mutableMapOf<String, Boolean>()

  fun clear() {
    sources.clear()
    images.clear()
  }

  fun sourceDefinition(id: String): SourceDefinition? = currentSource(id)?.definition

  fun sourceIds(): Set<String> =
    sources.filterValues { style.isCurrentLoadedStyle(it.binding) }.keys.toSet()

  private fun currentSource(id: String): OwnedSource? =
    sources[id]?.takeIf { style.isCurrentLoadedStyle(it.binding) }

  fun isExplicitImage(id: String): Boolean = images[id] == false

  fun requireNoConflicts(snapshot: StyleSnapshot) {
    snapshot.sources.firstOrNull { currentSource(it.id) != null }?.let {
      error("Source ID '${it.id}' is owned by an imperative addition")
    }
    snapshot.images.firstOrNull { images[it.id] == false }?.let {
      error("Image ID '${it.id}' is owned by an imperative addition")
    }
  }

  /** Submits the addition, then reads ordered metadata before returning its handle. */
  suspend fun add(source: Source): MutableSourceHandle? {
    requireSourceWritable(source.id)
    val binding = readyBinding("add source '${source.id}'") ?: return null
    val definition = source.definition()
    try {
      binding.addSource(definition)
    } catch (error: StyleMutationException) {
      throw StyleHandleException("Could not add source '${source.id}': ${error.message}", error)
    }
    sources[source.id] = OwnedSource(binding, definition)
    val committed = try {
      commitSources(binding)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      if (style.owner.isCurrent(binding)) throw error
      return null
    }
    if (!committed) return null
    return style.sourceHandle(source.id)?.asMutable
  }

  fun removeSource(id: String, binding: StyleBinding, identity: Any, onRemoved: () -> Unit) {
    requireSourceWritable(id)
    if (!style.owner.isCurrent(binding) || !binding.identity.sources.isCurrent(id, identity)) return
    try {
      binding.removeSource(id)
    } catch (error: StyleMutationException) {
      binding.reportRejectedWrite("Source '$id'", null, error)
      return
    }
    sources.remove(id)
    binding.identity.sources.remove(id)
    style.forgetSourceHandle(id)
    onRemoved()
    scope.launch { commitSources(binding) }
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
    if (writeImage(binding, id, null)) onRemoved()
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
    if (!style.isCurrentLoadedStyle(binding) || !binding.isLoaded ||
      !style.isImageWritable(id) || isExplicitImage(id)) return
    binding.setImage(StyleImageDefinition(id, image.image, image.sdf, image.stretch))
    images[id] = true
    binding.identity.images.remove(id)
  }

  private fun writeImage(binding: StyleBinding, id: String, definition: StyleImageDefinition?): Boolean {
    try {
      if (definition == null) binding.removeImage(id) else binding.setImage(definition)
    } catch (error: StyleMutationException) {
      binding.reportRejectedWrite("Image '$id'", null, error)
      return false
    }
    if (definition == null) images.remove(id) else images[id] = false
    binding.identity.images.remove(id)
    return true
  }

  private fun readyBinding(target: String): StyleBinding? {
    style.owner.requireOpen()
    val binding = style.readyLoadedStyle()
    if (binding == null) style.owner.logger?.w { "Skipped the command to $target: no style is ready" }
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
