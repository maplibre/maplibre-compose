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

  private var removeUnloadAction: (() -> Unit)? = null

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
    // Before the owner-thread hop: GeoJSON parse and index must not run on the pump. The handle
    // is a local here, then either addTo consumes it on the owner thread or release() closes it
    // after mutateMap returns. mutateMap waits until that hop has run or been dropped.
    var pending: AutoCloseable? =
      if (!isAttached) {
        try {
          prepareForAttach()
        } catch (error: Throwable) {
          throw wrapAddError(error)
        }
      } else {
        null
      }
    fun release() {
      val handle = pending ?: return
      pending = null
      handle.close()
    }
    val added =
      try {
        binding.mutateMap { map ->
          // A layer may attach its source before the source effect runs, so re-attaching the same
          // binding is idempotent; any other descriptor with this ID is rejected.
          if (this.binding === binding && map.styleSourceExists(id)) return@mutateMap false
          check(!map.styleSourceExists(id)) {
            "Source ID '$id' is already owned by a different live source descriptor"
          }
          val prepared = pending
          pending = null
          try {
            addTo(map, prepared)
          } catch (error: Throwable) {
            throw wrapAddError(error)
          }
          binding.reportSourceChanged(id)
          true
        }
      } catch (error: Throwable) {
        release()
        throw error
      }
    if (added != true) release()
    check(added != null) {
      "Source '$id' was not added: its style is no longer loaded. Any layer referencing it will " +
        "fail to attach."
    }
    if (!added) return
    this.binding = binding
    removeUnloadAction?.invoke()
    attachedToStyle(binding)
    val unregister = binding.onUnload {
      if (this.binding === binding) {
        this.binding = MlnFfiStyleBinding.UNLOADED
        removeUnloadAction = null
        detachedFromStyle()
      }
    }
    if (this.binding === binding) removeUnloadAction = unregister else unregister()
  }

  /** Native reports what was wrong with the definition but never whose. */
  private fun wrapAddError(error: Throwable): Throwable =
    if (error is MaplibreException) {
      IllegalStateException(
        "Could not add source '$id' of type " +
          "'${(toJson()["type"] as? JsonPrimitive)?.content}': ${error.message}",
        error,
      )
    } else {
      error
    }

  /**
   * Runs on the caller before [addTo] hops to the owner thread. Override to do work that must not
   * run on the pump, such as GeoJSON parse and index. The returned handle is consumed by [addTo] or
   * closed by [attach] after the hop has run or been dropped.
   */
  internal open fun prepareForAttach(): AutoCloseable? = null

  /**
   * Creates this source on [map], on the map's owner thread. [prepared] is the handle
   * [prepareForAttach] returned, or null; this call consumes it. MapLibre Native accepts only
   * `vector`, `raster`, `raster-dem`, `geojson`, and `image` from source JSON; any other source
   * type must override this with its typed `MapHandle` adder.
   */
  internal open fun addTo(map: MapHandle, prepared: AutoCloseable? = null) {
    prepared?.close()
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
    if (binding === MlnFfiStyleBinding.UNLOADED && !expectedBinding.isLoaded) return
    require(binding === expectedBinding) {
      "Source '$id' does not belong to the style trying to remove it"
    }
    val current = binding
    current.mutateMap { map ->
      map.removeStyleSource(id)
      current.forgetFeatureStates(id)
      current.reportSourceChanged(id)
    }
    removeUnloadAction?.invoke()
    removeUnloadAction = null
    binding = MlnFfiStyleBinding.UNLOADED
    detachedFromStyle()
  }

  /** Called after this descriptor has attached to a loaded style. */
  internal open fun attachedToStyle(binding: MlnFfiStyleBinding) = Unit

  /** Called after explicit removal or when the attached style unloads. */
  internal open fun detachedFromStyle() = Unit

  /**
   * Applies [update] to the live source. Returns false when the style has unloaded, which is normal
   * for a frame during a style swap.
   */
  protected fun mutate(update: (map: MapHandle) -> Unit): Boolean =
    binding.mutateMap(update) != null

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
