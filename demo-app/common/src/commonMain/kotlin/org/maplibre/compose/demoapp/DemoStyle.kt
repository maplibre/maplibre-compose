package org.maplibre.compose.demoapp

import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.style.BaseStyle

/** The styles the user can pick from anywhere, independent of the selected demo. */
interface DemoStyle {
  val displayName: String
  val base: BaseStyle
  val isDark: Boolean

  /**
   * The font stack a demo's own symbol layers should ask for. Per-style, because each style's glyph
   * endpoint serves a different set of font names.
   */
  val textFont: List<String>
    get() = listOf("Noto Sans Regular")

  /** An anchor that keeps a demo's layers below the style's labels. */
  val anchorBelowSymbols: Anchor
}

/**
 * The styles the user can pick from, in picker order.
 *
 * Top-level rather than on [DemoStyle] because a list of implementors must not live on the type
 * they implement: initializing an enum entry first initializes the interface, and reading `entries`
 * there would re-enter the enum before its entries array is assigned.
 */
val allDemoStyles: List<DemoStyle> =
  OpenFreeMap.entries + Protomaps.entries + Versatiles.entries + OtherStyles.entries

enum class OpenFreeMap(override val isDark: Boolean = false) : DemoStyle {
  Bright,
  Liberty,
  Positron;

  override val displayName = "$name (OpenFreeMap)"

  override val base = BaseStyle.Uri("https://tiles.openfreemap.org/styles/${name.lowercase()}")

  override val anchorBelowSymbols = Anchor.Below("waterway_line_label")
}

enum class Protomaps(override val isDark: Boolean = false) : DemoStyle {
  Light,
  Dark(true),
  White,
  Grayscale,
  Black(true);

  override val displayName = "$name (Protomaps)"

  override val base =
    BaseStyle.Uri(
      "https://api.protomaps.com/styles/v5/${name.lowercase()}/en.json?key=73c45a97eddd43fb"
    )

  override val anchorBelowSymbols = Anchor.Below("address_label")
}

enum class Versatiles(override val isDark: Boolean = false) : DemoStyle {
  Colorful,
  Eclipse(true),
  Graybeard;

  override val displayName = "$name (Versatiles)"

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
