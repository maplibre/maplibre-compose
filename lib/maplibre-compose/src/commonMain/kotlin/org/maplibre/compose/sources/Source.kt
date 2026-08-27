package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleMutationException

/**
 * A data source for map data.
 *
 * One instance may belong to only one loaded map style at a time. Create or remember a separate
 * source inside each [MaplibreMap][org.maplibre.compose.map.MaplibreMap] that uses it.
 */
public sealed class Source(internal val id: String) {

  /**
   * This source's definition as style JSON, used to add it and to answer reads before attachment.
   */
  internal abstract fun toJson(): JsonObject

  @Volatile
  internal var binding: StyleBinding = StyleBinding.UNLOADED
    private set

  private var removeUnloadAction: (() -> Unit)? = null

  /** Whether this source currently belongs to a loaded style. */
  internal val isAttached: Boolean
    get() = binding.isLoaded

  public val attributionHtml: String
    get() = (toJson()["attribution"] as? JsonPrimitive)?.content.orEmpty()

  /** Adds this source to a style and starts routing mutations to it. */
  internal fun attach(binding: StyleBinding) {
    check(this.binding === binding || !this.binding.isLoaded) {
      "Source '$id' already belongs to another loaded style; create a separate source instance " +
        "for each map"
    }
    // Defends against a re-entrant attach of the same source to the same style, not ordering.
    // Decided before the engine query so a concurrent unload cannot turn the re-entry into a
    // duplicate-id failure.
    if (this.binding === binding) return
    val exists = binding.sourceExists(id)
    // Null means the check could not run; the add still refuses a duplicate.
    check(exists != true) {
      "Source ID '$id' is already owned by a different live source descriptor"
    }
    val added =
      try {
        addTo(binding)
      } catch (error: StyleMutationException) {
        throw IllegalStateException(
          "Could not add source '$id' of type " +
            "'${(toJson()["type"] as? JsonPrimitive)?.content}': ${error.message}",
          error,
        )
      }
    // The style can unload on another thread between the caller's loaded check and the add; the
    // dropped write is the unload contract, not an error.
    if (!added) {
      binding.logger?.w { "Source '$id' was not added: its style unloaded first." }
      return
    }
    this.binding = binding
    removeUnloadAction?.invoke()
    val unregister = binding.onUnload {
      if (this.binding === binding) {
        this.binding = StyleBinding.UNLOADED
        removeUnloadAction = null
      }
    }
    if (this.binding === binding) removeUnloadAction = unregister else unregister()
  }

  /**
   * Creates this source in the style. Override for a source whose definition cannot travel in style
   * JSON, such as one carrying pixels or a tile callback, and call the binding's typed adder.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   */
  internal open fun addTo(binding: StyleBinding): Boolean = binding.addSource(id, toJson())

  /** Binds this descriptor to a source already in the style, without adding it. */
  internal fun bindExisting(binding: StyleBinding) {
    check(this.binding === binding || !this.binding.isLoaded) {
      "Source '$id' already belongs to another loaded style"
    }
    this.binding = binding
  }

  /** Removes this source from its style; the descriptor survives for a later style. */
  internal fun detach(expectedBinding: StyleBinding) {
    if (binding === StyleBinding.UNLOADED && !expectedBinding.isLoaded) return
    require(binding === expectedBinding) {
      "Source '$id' does not belong to the style trying to remove it"
    }
    binding.removeSource(id)
    removeUnloadAction?.invoke()
    removeUnloadAction = null
    binding = StyleBinding.UNLOADED
  }

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}

/**
 * Returns the source with the given [id] from the base style that
 * [MapState.baseStyle][org.maplibre.compose.map.MapState.baseStyle] selects, or null when the
 * loaded style has no such source.
 *
 * This form keys on the loaded style binding, so a style swap returns the new base style's source;
 * [MapState.sources][org.maplibre.compose.map.MapState.sources] reads the same sources outside the
 * style content.
 */
@Composable
public fun getBaseSource(id: String): Source? {
  val node = LocalStyleNode.current
  return remember(node, node.binding, id) { node.sourceManager.getBaseSource(id) }
}

@Composable
internal fun <T : Source> rememberUserSource(factory: (String) -> T, update: T.() -> Unit): T {
  val node = LocalStyleNode.current
  val source = remember(node) { factory(node.sourceManager.nextId()) }
  LaunchedEffect(source, update, node.binding) {
    if (node.binding.isLoaded) source.update()
  }
  return source
}

public object SourceDefaults {
  public const val MIN_ZOOM: Int = 0
  public const val MAX_ZOOM: Int = 18
  public const val RASTER_TILE_SIZE: Int = 512
}
