package org.maplibre.compose.sources

import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.toJsonBytes
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.map.MapHandle

public actual sealed class Source(internal actual val id: String) {

  /**
   * This source's definition as style JSON, used to add it and to answer reads before attachment.
   */
  internal abstract fun toJson(): JsonObject

  @Volatile
  internal var binding: MlnFfiStyleBinding = MlnFfiStyleBinding.UNLOADED
    private set

  /** Whether this source currently belongs to a loaded style. */
  internal val isAttached: Boolean
    get() = binding.isLoaded

  public actual val attributionHtml: String
    get() = (toJson()["attribution"] as? JsonPrimitive)?.content.orEmpty()

  /** Adds this source to a style and starts routing mutations to it. */
  internal fun attach(binding: MlnFfiStyleBinding) {
    check(this.binding === binding || !this.binding.isLoaded) {
      "Source '$id' already belongs to another loaded style; create a separate source instance " +
        "for each map"
    }
    val added = binding.mutateMap { map ->
      // A layer may attach its source before the source effect runs, so re-attaching the same
      // binding is idempotent; any other descriptor with this ID is rejected.
      if (this.binding === binding && map.styleSourceExists(id)) return@mutateMap false
      check(!map.styleSourceExists(id)) {
        "Source ID '$id' is already owned by a different live source descriptor"
      }
      try {
        addTo(map)
      } catch (error: Throwable) {
        if (error is MaplibreException) {
          // Native reports what was wrong with the definition but never whose.
          throw IllegalStateException(
            "Could not add source '$id' of type " +
              "'${(toJson()["type"] as? JsonPrimitive)?.content}': ${error.message}",
            error,
          )
        }
        throw error
      }
      true
    }
    check(added != null) {
      "Source '$id' was not added: its style is no longer loaded. Any layer referencing it will " +
        "fail to attach."
    }
    if (!added) return
    this.binding = binding
  }

  /**
   * Creates this source on [map], on the map's owner thread. MapLibre Native accepts only `vector`,
   * `raster`, `raster-dem`, `geojson`, and `image` from source JSON; any other source type must
   * override this with its typed `MapHandle` adder.
   */
  internal open fun addTo(map: MapHandle) {
    map.addStyleSourceJson(id, toJson().toJsonBytes())
  }

  /** Binds this descriptor to a source already in the style, without adding it. */
  internal fun bindExisting(binding: MlnFfiStyleBinding) {
    check(this.binding === binding || !this.binding.isLoaded) {
      "Source '$id' already belongs to another loaded style"
    }
    this.binding = binding
  }

  /** Removes this source from its style; the descriptor survives for a later style. */
  internal fun detach(expectedBinding: MlnFfiStyleBinding) {
    require(binding === expectedBinding) {
      "Source '$id' does not belong to the style trying to remove it"
    }
    binding.mutateMap { map -> map.removeStyleSource(id) }
    binding = MlnFfiStyleBinding.UNLOADED
  }

  /**
   * Applies [update] to the live source. Returns false when the style has unloaded, which is normal
   * for a frame during a style swap.
   */
  protected fun mutate(update: (map: MapHandle) -> Unit): Boolean =
    binding.mutateMap(update) != null

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
