package org.maplibre.compose.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

public sealed interface BaseStyle {

  public class Uri(public val uri: String) : BaseStyle

  public class Json(public val json: String) : BaseStyle {

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
