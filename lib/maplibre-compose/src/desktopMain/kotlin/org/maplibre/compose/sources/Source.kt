package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.toFfiJsonValue
import org.maplibre.nativeffi.map.MapHandle

/**
 * A data source, as a live descriptor.
 *
 * Before the source is added to a style it holds its own definition, so a source can be created and
 * configured during composition before any style exists. [attach] hands it to MapLibre, after which
 * mutations go straight through and the descriptor records what was sent.
 */
public actual sealed class Source(internal actual val id: String) {

  /**
   * This source's definition as style JSON, used to add it and to answer reads before attachment.
   */
  internal abstract fun toJson(): JsonObject

  internal var binding: StyleBinding = StyleBinding.UNLOADED
    private set

  /** Whether this source currently belongs to a loaded style. */
  internal val isAttached: Boolean
    get() = binding.isLoaded

  public actual val attributionHtml: String
    get() = (toJson()["attribution"] as? JsonPrimitive)?.content.orEmpty()

  /**
   * Adds this source to a style and starts routing mutations to it.
   *
   * TODO(maplibre-native-ffi): accepted upstream and queued; use the typed per-kind adders once
   *   they can express what the common API offers. `addGeoJsonSourceUrl` and `addGeoJsonSourceData`
   *   take no options at all and there is no `GeoJsonSourceOptions` type, so clustering, buffer,
   *   tolerance, and line metrics are unreachable through them — a clustered source cannot be
   *   created that way. The generic JSON entry point is the only one that carries `GeoJsonOptions`,
   *   and the other families use it too so there is one attach path rather than two. Mutations
   *   already use the typed setters.
   */
  internal fun attach(binding: StyleBinding) {
    this.binding = binding
    val added = binding.withMap { map ->
      // Idempotent, because a layer attaches its own source first when Compose has not run the
      // source's effect yet; the effect then attaches the same source again.
      if (!map.styleSourceExists(id)) {
        map.addStyleSourceJson(id, toJson().toFfiJsonValue())
      }
    }
    check(added != null) {
      "Source '$id' was not added: its style is no longer loaded. Any layer referencing it will " +
        "fail to attach."
    }
  }

  /**
   * Binds this descriptor to a source that is already in the style, without adding it.
   *
   * Used when reading back the base style: those sources already exist in MapLibre, so adding them
   * again would either duplicate them or be rejected.
   */
  internal fun bindExisting(binding: StyleBinding) {
    this.binding = binding
  }

  /**
   * Removes this source from its style.
   *
   * The descriptor survives, so the source can be added to a later style — which is what happens
   * when the base style changes and the composition re-adds its content.
   */
  internal fun detach() {
    binding.withMap { map -> map.removeStyleSource(id) }
    binding = StyleBinding.UNLOADED
  }

  /**
   * Applies [update] to the live source, reporting whether there was one to apply it to.
   *
   * Returns false when the style has unloaded. Callers surface that as a diagnostic rather than
   * failing: a source outliving its style by a frame is normal during a style swap.
   */
  protected fun mutate(update: (map: MapHandle) -> Unit): Boolean = binding.withMap(update) != null

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}
