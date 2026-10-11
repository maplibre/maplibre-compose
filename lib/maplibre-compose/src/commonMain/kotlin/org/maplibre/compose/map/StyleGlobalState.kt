package org.maplibre.compose.map

import androidx.compose.runtime.Stable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.InternalGlobalStatePrefix

/**
 * Application values shared by expressions in the current loaded style.
 *
 * Names beginning with `maplibre-compose:` are reserved for the library and omitted from [get].
 *
 * The base style's root `state` object supplies defaults. A base-style reload discards runtime
 * values and loads the new defaults. This object follows the current style.
 *
 * Writes do not wait for the engine. Reads await earlier writes. A write while no style is ready
 * does nothing and logs a warning. Engine rejections are logged.
 */
@Stable
public class StyleGlobalState internal constructor(private val style: MapStyleState) {
  /**
   * Returns a snapshot of application values, including style defaults, or null if no style is
   * ready or the style changes during the read.
   */
  public suspend fun get(): JsonObject? =
    style.globalStateValues()?.let { state ->
      JsonObject(state.filterKeys { !it.startsWith(InternalGlobalStatePrefix) })
    }

  /**
   * Sets the global state property [name] to [value].
   *
   * @param value Arrays and objects are stored as data, not evaluated as style expressions.
   *   [JsonNull] restores the style's default for [name], or null when no default exists.
   */
  public fun setProperty(name: String, value: JsonElement) {
    require(!name.startsWith(InternalGlobalStatePrefix)) {
      "Global-state names beginning with '$InternalGlobalStatePrefix' are reserved, was '$name'"
    }
    style.setGlobalStateProperty(name, value)
  }

  /** Restores [name] to its style default, or null when no default exists. */
  public fun resetProperty(name: String) {
    setProperty(name, JsonNull)
  }
}
