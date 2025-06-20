package dev.sargunv.maplibrecompose.demoapp

import dev.sargunv.maplibrecompose.compose.layer.Anchor
import dev.sargunv.maplibrecompose.core.BaseStyle
import dev.sargunv.maplibrecompose.demoapp.generated.Res

interface StyleInfo {
  val displayName: String
  val base: BaseStyle
  val isDark: Boolean
  val anchorBelowSymbols: Anchor
}

enum class Protomaps(override val isDark: Boolean = false) : StyleInfo {
  Light,
  Dark(true),
  White,
  Grayscale,
  Black(true);

  override val displayName = name

  override val base =
    BaseStyle.Uri(
      "https://api.protomaps.com/styles/v5/${name.lowercase()}/en.json?key=73c45a97eddd43fb"
    )

  override val anchorBelowSymbols = Anchor.Below("address_label")
}

enum class OpenFreeMap(override val isDark: Boolean = false) : StyleInfo {
  Bright,
  Liberty,
  Positron,
  Fiord(true),
  Dark(true);

  override val displayName = name

  override val base = BaseStyle.Uri("https://tiles.openfreemap.org/styles/${name.lowercase()}")

  override val anchorBelowSymbols = Anchor.Below("waterway_line_label")
}

enum class Versatiles(override val isDark: Boolean = false) : StyleInfo {
  Colorful,
  Eclipse(true),
  Graybeard,
  Neutrino;

  override val displayName = name

  override val base = BaseStyle.Uri(Res.getUri("files/styles/${name.lowercase()}.json"))

  override val anchorBelowSymbols = Anchor.Below("label-address-housenumber")
}

enum class OtherStyles(
  override val displayName: String,
  override val base: BaseStyle,
  override val isDark: Boolean = false,
  override val anchorBelowSymbols: Anchor = Anchor.Top,
) : StyleInfo {
  OpenStreetMaps(
    displayName = "OpenStreetMaps Carto",
    base = BaseStyle.Uri(Res.getUri("files/styles/osm-raster.json")),
  )
}
