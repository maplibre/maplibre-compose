package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.StyleMutationException

/** Holds its own definition until [attach]; after that, mutations go straight to MapLibre. */
public actual sealed class Source(internal actual val id: String) {

  internal abstract fun toJson(): JsonObject

  internal var binding: GlJsStyleBinding? = null
    private set

  internal val isAttached: Boolean
    get() = binding?.isLoaded == true

  public actual val attributionHtml: String
    get() = (toJson()["attribution"] as? JsonPrimitive)?.content.orEmpty()

  internal fun attach(binding: GlJsStyleBinding) {
    val current = this.binding
    check(current === binding || current?.isLoaded != true) {
      "Source '$id' already belongs to another loaded style; create a separate source instance " +
        "for each map"
    }
    check(binding.isLoaded) {
      "Source '$id' was not added: its style is no longer loaded. Any layer referencing it will " +
        "fail to attach."
    }
    // A layer may attach its source before the source effect runs, or use a descriptor read from
    // the base style.
    if (binding.sourceExists(id)) {
      check(current === binding) {
        "Source ID '$id' is already owned by a different live source descriptor"
      }
      return
    }
    try {
      addTo(binding)
    } catch (error: StyleMutationException) {
      throw IllegalStateException(
        "Could not add source '$id' of type " +
          "'${(toJson()["type"] as? JsonPrimitive)?.content}': ${error.message}",
        error,
      )
    }
    this.binding = binding
  }

  /** Overridable for sources whose pixels cannot travel in style JSON. */
  internal open fun addTo(binding: GlJsStyleBinding) {
    binding.addSource(id, toJson())
  }

  internal fun bindExisting(binding: GlJsStyleBinding) {
    val current = this.binding
    check(current === binding || current?.isLoaded != true) {
      "Source '$id' already belongs to another loaded style"
    }
    this.binding = binding
  }

  /** The descriptor survives for a later style. */
  internal fun detach(expectedBinding: GlJsStyleBinding) {
    require(binding === expectedBinding) {
      "Source '$id' does not belong to the style trying to remove it"
    }
    expectedBinding.removeSource(id)
    binding = null
  }

  /** False when the style has unloaded, which is normal for a frame during a style swap. */
  internal fun mutate(update: (map: MaplibreMap) -> Unit): Boolean =
    binding?.withMap(update) != null

  internal fun <T : Any> liveSource(): T? = binding?.withMap { map -> map.getSource<T>(id) }

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
