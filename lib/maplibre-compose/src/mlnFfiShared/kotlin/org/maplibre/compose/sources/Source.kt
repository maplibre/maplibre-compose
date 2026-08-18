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
    // Existence is read synchronously so a duplicate ID still fails on the caller. The add itself
    // is posted; a same-frame mutate then queues behind it. Null means the read did not complete —
    // a gate wait may end early on interruption — and an unknown answer must not read as absence.
    val exists = binding.readMap { it.styleSourceExists(id) }
    check(exists != null) { "Could not confirm whether source '$id' already exists; not attaching" }
    if (this.binding === binding && exists) return
    check(!exists) { "Source ID '$id' is already owned by a different live source descriptor" }
    // Expensive preparation runs on the caller, not the map's owner thread. A posted mutation
    // always runs once accepted, so the prepared work is consumed by addTo or closed here.
    prepareForAttach()
    val posted =
      binding.mutateMap(
        // The loop can tear down between acceptance and execution; the prepared data would
        // otherwise leak, because only addTo consumes it.
        onAbandon = ::abandonPrepareForAttach
      ) { map ->
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
        binding.notifySourceChanged(id)
      }
    if (!posted) abandonPrepareForAttach()
    check(posted) {
      "Source '$id' was not added: its style is no longer loaded. Any layer referencing it will " +
        "fail to attach."
    }
    this.binding = binding
  }

  /** Runs before the add is queued, on the caller thread; overridden by sources that pre-parse. */
  internal open fun prepareForAttach() {}

  /** Releases work held by [prepareForAttach] when the add was not queued. */
  internal open fun abandonPrepareForAttach() {}

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
    val sessionBinding = binding
    binding = MlnFfiStyleBinding.UNLOADED
    sessionBinding.mutateMap { map ->
      map.removeStyleSource(id)
      sessionBinding.notifySourceChanged(id)
    }
  }

  /**
   * Applies [update] to the live source. Returns false when the style has unloaded, which is normal
   * for a frame during a style swap.
   */
  protected fun mutate(
    onAbandon: () -> Unit = {},
    update: (map: MapHandle) -> Unit,
  ): Boolean = binding.mutateMap(onAbandon, update)

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
