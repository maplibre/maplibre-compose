package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.TransitionOptions

/** Reads a layer in one loaded style generation. Handles expire on removal or replacement. */
public sealed interface LayerHandle {
  public val id: String
  public val type: String
  /** The source ID, or null for a layer without one. */
  public val source: String?
  /** The source layer, or null when none is specified. */
  public val sourceLayer: String?
  /**
   * Definition writes, or null for a declared layer or a handle supplied to an anchor predicate.
   */
  public val asMutable: MutableLayerHandle?

  /** Returns the current value of [name], or null when the layer has no value for that property. */
  public suspend fun getProperty(name: String): JsonElement?

  /**
   * Returns the transition of the paint property [property], named without the `-transition`
   * suffix.
   *
   * Returns null when the layer states no transition for that property, and when it states one
   * without both a duration and a delay: an engine times the omitted field with the style's global
   * transition, which [TransitionOptions] states no value for. MapLibre GL JS reports an empty
   * object for a transition that was cleared, and this returns null for it.
   *
   * The reported timing is the engine's: a transition that the library wrote is under the animator
   * duration scale of the time it was written.
   */
  public suspend fun getPaintTransition(property: String): TransitionOptions?
}

/**
 * Definition writes for a layer that composition does not own. Writes do not wait for the engine;
 * rejected values are logged and retain the previous value. The view expires with its read handle.
 */
public sealed interface MutableLayerHandle : LayerHandle {
  /** Sets the layout property [name] for this loaded style. */
  public fun setLayoutProperty(name: String, value: JsonElement): Unit

  /** Sets the paint property [name] for this loaded style. */
  public fun setPaintProperty(name: String, value: JsonElement): Unit

  /**
   * Sets the transition of the paint property [property], named without the `-transition` suffix. A
   * null [options] returns the property to the style's global transition. The reset is written at
   * once, as the spec's empty transition object; a layer composable drops the key from its layer
   * definition instead, with the same result.
   *
   * On Android, the system animator duration scale multiplies the timing that reaches the engine. A
   * scale of zero applies the property change instantly.
   */
  public fun setPaintTransition(property: String, options: TransitionOptions?): Unit

  /**
   * Sets the top-level property [name], such as `minzoom`, for this loaded style.
   *
   * @throws StyleHandleException if [name] is `source` or `source-layer`: [source] and
   *   [sourceLayer] are fixed for the layer's generation.
   */
  public fun setRootProperty(name: String, value: JsonElement): Unit

  /** Removes the layer filter for this loaded style. */
  public fun clearFilter(): Unit
}
