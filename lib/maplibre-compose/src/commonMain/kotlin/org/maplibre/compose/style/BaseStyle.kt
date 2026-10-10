package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A MapLibre style document for the map to load, given by [Uri] or inline as [Json].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface BaseStyle {

  /**
   * A style document that the map loads from [uri].
   *
   * @property uri MapLibre Native fetches `http:` and `https:` URIs over the network and reads
   *   other URIs, such as `file:` URIs and the URIs that Compose Multiplatform's `Res.getUri`
   *   returns, as packaged resources. In the browser, MapLibre GL JS fetches [uri].
   */
  @Immutable public data class Uri(public val uri: String) : BaseStyle

  /** A style document given as JSON text, a [JsonObject], or a JSON builder. */
  @Immutable
  public data class Json(public val json: String) : BaseStyle {

    public constructor(json: JsonObject) : this(json.toString())

    public constructor(
      builderAction: JsonObjectBuilder.() -> Unit
    ) : this(buildJsonObject(builderAction))
  }

  public companion object {
    public val Demo: Uri = Uri("https://demotiles.maplibre.org/style.json")
    public val Empty: Json = Json {
      put("version", 8)
      put("name", "MapLibre Compose")
      putJsonObject("metadata") {}
      putJsonObject("sources") {}
      putJsonArray("layers") {}
    }
  }
}

/** Keeps [BaseStyle] open: callers' `when` needs an `else` branch. The library never creates it. */
internal data object UnspecifiedBaseStyle : BaseStyle
