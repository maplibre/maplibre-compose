package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

/**
 * Configuration for tile server endpoints and URL templates. This configures how MapLibre connects
 * to map data providers.
 */
@ExposeToNative
public data class TileServerOptions(
  @get:CalledByNative val baseURL: String = "https://api.mapbox.com",
  @get:CalledByNative val uriSchemeAlias: String = "mapbox://",
  @get:CalledByNative val apiKeyParameterName: String = "access_token",
  @get:CalledByNative val requiresApiKey: Boolean = false,
  @get:CalledByNative
  val sourceTemplate: TileServerTemplate = TileServerTemplate("https://api.mapbox.com"),
  @get:CalledByNative
  val styleTemplate: TileServerTemplate = TileServerTemplate("/styles/v1/{user}/{style}"),
  @get:CalledByNative
  val spritesTemplate: TileServerTemplate = TileServerTemplate("/styles/v1/{user}/{style}/sprite"),
  @get:CalledByNative
  val glyphsTemplate: TileServerTemplate =
    TileServerTemplate("/fonts/v1/{user}/{fontstack}/{range}.pbf"),
  @get:CalledByNative
  val tileTemplate: TileServerTemplate =
    TileServerTemplate("/v4/{tileset}/{z}/{x}/{y}{ratio}.{format}"),
  @get:CalledByNative val defaultStyle: String = "",
) {
  public companion object {
    public val DemoTiles: TileServerOptions =
      TileServerOptions(
        baseURL = "https://demotiles.maplibre.org",
        uriSchemeAlias = "maplibre://",
        apiKeyParameterName = "",
        requiresApiKey = false,
        sourceTemplate = TileServerTemplate("https://demotiles.maplibre.org"),
        styleTemplate = TileServerTemplate("/style.json"),
        spritesTemplate = TileServerTemplate("/sprite"),
        glyphsTemplate = TileServerTemplate("/fonts/{fontstack}/{range}.pbf"),
        tileTemplate = TileServerTemplate("/tiles/{z}/{x}/{y}.pbf"),
      )

    public val Mapbox: TileServerOptions =
      TileServerOptions(
        baseURL = "https://api.mapbox.com",
        uriSchemeAlias = "mapbox://",
        apiKeyParameterName = "access_token",
        requiresApiKey = true,
        sourceTemplate = TileServerTemplate("https://api.mapbox.com"),
        styleTemplate = TileServerTemplate("/styles/v1/{user}/{style}"),
        spritesTemplate = TileServerTemplate("/styles/v1/{user}/{style}/sprite"),
        glyphsTemplate = TileServerTemplate("/fonts/v1/{user}/{fontstack}/{range}.pbf"),
        tileTemplate = TileServerTemplate("/v4/{tileset}/{z}/{x}/{y}{ratio}.{format}"),
      )

    public val MapTiler: TileServerOptions =
      TileServerOptions(
        baseURL = "https://api.maptiler.com",
        uriSchemeAlias = "maptiler://",
        apiKeyParameterName = "key",
        requiresApiKey = true,
        sourceTemplate = TileServerTemplate("https://api.maptiler.com"),
        styleTemplate = TileServerTemplate("/maps/{style}/style.json"),
        spritesTemplate = TileServerTemplate("/maps/{style}/sprite"),
        glyphsTemplate = TileServerTemplate("/fonts/{fontstack}/{range}.pbf"),
        tileTemplate = TileServerTemplate("/tiles/{tileset}/{z}/{x}/{y}.pbf"),
      )
  }
}
