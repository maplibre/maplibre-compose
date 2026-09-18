package org.maplibre.compose.map

import androidx.compose.runtime.Stable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Values shared by expressions in the current loaded style.
 *
 * The base style's root `state` object supplies defaults. A base-style reload discards runtime
 * values and loads the new defaults. This object follows the current style.
 *
 * Writes require a ready loaded style and do not wait for the engine. Native applies them on its
 * owner thread; reads await earlier writes. Engine rejections are logged.
 */
@Stable
public class StyleGlobalState internal constructor(private val style: MapStyleState) {
  /**
   * Returns a snapshot of effective values, including defaults, or null if no style is ready or the
   * style changes during the read. An empty object means the loaded style has no state.
   */
  public suspend fun get(): JsonObject? = style.globalStateValues()

  /**
   * Arrays and objects are stored as data, not evaluated as style expressions. [JsonNull] restores
   * the style's default for [name], or null when no default exists.
   *
   * @throws IllegalStateException if no style is ready.
   */
  public fun setProperty(name: String, value: JsonElement) {
    style.setGlobalStateProperty(name, value)
  }

  /**
   * Restores [name] to its style default, or null when no default exists. Requires a ready style.
   */
  public fun resetProperty(name: String) {
    setProperty(name, JsonNull)
  }
}
