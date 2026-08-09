package org.maplibre.compose.demoapp

import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.style.BaseStyle

interface DemoStyle {
  val displayName: String
  val base: BaseStyle
  val isDark: Boolean
  val anchorBelowSymbols: Anchor

  /**
   * The font stack a demo's own symbol layers should ask for. Per-style, because each style's glyph
   * endpoint serves a different set of font names.
   */
  val textFont: List<String>
    get() = listOf("Noto Sans Regular")
}

enum class Protomaps(override val isDark: Boolean = false) : DemoStyle {
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

enum class OpenFreeMap(override val isDark: Boolean = false) : DemoStyle {
  Bright,
  Liberty,
  Positron;

  override val displayName = name

  override val base = BaseStyle.Uri("https://tiles.openfreemap.org/styles/${name.lowercase()}")

  override val anchorBelowSymbols = Anchor.Below("waterway_line_label")
}

enum class Versatiles(override val isDark: Boolean = false) : DemoStyle {
  Colorful,
  Eclipse(true),
  Graybeard;

  override val displayName = name

  override val base = BaseStyle.Uri(Res.getUri("files/styles/${name.lowercase()}.json"))

  override val anchorBelowSymbols = Anchor.Below("label-address-housenumber")

  // Versatiles names its fonts in snake case; "Noto Sans Regular" 404s here.
  override val textFont = listOf("noto_sans_regular")
}

enum class OtherStyles(
  override val displayName: String,
  override val base: BaseStyle,
  override val isDark: Boolean = false,
  override val anchorBelowSymbols: Anchor = Anchor.Top,
  override val textFont: List<String> = listOf("Noto Sans Regular"),
) : DemoStyle {
  // A raster style with no `glyphs` endpoint, so no font stack works here and the value is inert.
  OpenStreetMaps(
    displayName = "OpenStreetMaps Carto",
    base = BaseStyle.Uri(Res.getUri("files/styles/osm-raster.json")),
  ),
  Americana(
    displayName = "Americana",
    base = BaseStyle.Uri("https://americanamap.org/style.json"),
    textFont = listOf("Americana-Regular"),
  ),
}
