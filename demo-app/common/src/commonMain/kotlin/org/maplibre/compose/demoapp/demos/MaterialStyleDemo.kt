package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.PROTOMAPS_API_KEY
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.any
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.contains
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.lte
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.DpValue
import org.maplibre.compose.expressions.value.GeometryType
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.expressions.value.TextTransform
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.FillExtrusionLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.rememberVectorSource
import org.maplibre.compose.style.BaseStyle

/**
 * The empty base style for this demo. Only the glyph endpoint lives in the JSON, because symbol
 * layers cannot render text without one. Both entries emit identical JSON, so switching between
 * them re-tints the map by recomposition alone, without a style reload.
 *
 * Deliberately absent from [allDemoStyles]: the style is blank without this demo's layers, so it
 * should not be pickable from the global style list.
 */
private enum class Material(override val isDark: Boolean = false) : DemoStyle {
  Light,
  Dark(true);

  override val displayName = "Material 3 ($name)"

  override val base = BaseStyle.Json {
    put("version", 8)
    put("name", "Material 3")
    put("glyphs", "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf")
    putJsonObject("sources") {}
    putJsonArray("layers") {}
  }

  override val anchorBelowSymbols = Anchor.Top
}

/**
 * A complete basemap — land, water, roads, buildings, and labels — composed in Kotlin from
 * MaterialTheme color tokens. The layer structure follows Protomaps Light; the street and building
 * shading follows Protomaps White and Black. The only style JSON is an empty base style, so the
 * whole map re-tints by recomposition when the theme changes.
 */
object MaterialStyleDemo : Demo {
  override val name = "Material 3 style"
  override val description =
    "An entire basemap drawn from MaterialTheme colors. Toggle dark theme to re-tint it live."

  /** Backed by demo state, so the panel's dark toggle re-tints the map and the shell together. */
  private var dark by mutableStateOf(false)

  private var extrudeBuildings by mutableStateOf(false)

  override val preferredStyle: DemoStyle
    get() = if (dark) Material.Dark else Material.Light

  // A worldwide basemap, so keep the camera wherever it is.
  override val destination = DemoDestination.None

  private const val TILES = "https://api.protomaps.com/tiles/v4.json"

  private val kind = feature["kind"].asString()
  private val kindDetail = feature["kind_detail"].asString()
  private val isTunnel = feature.has("is_tunnel")
  private val isLink = feature.has("is_link")
  private val isPolygon =
    any(
      feature.geometryType() eq const(GeometryType.Polygon),
      feature.geometryType() eq const(GeometryType.MultiPolygon),
    )
  // Fallbacks belong inside the string assertion: ["string", a, b] tries each in turn, while a
  // failed ["string", a] inside coalesce aborts the whole expression and drops the label.
  private val label = feature["name:en"].asString(feature["name"])

  private fun isKind(vararg kinds: String): Expression<BooleanValue> =
    const(kinds.toList()).contains(kind)

  private fun roadWidth(vararg stops: Pair<Number, Expression<DpValue>>) =
    interpolate(exponential(1.6f), zoom(), *stops)

  @Composable
  override fun MapContent() {
    val tiles = rememberVectorSource("$TILES?key=$PROTOMAPS_API_KEY")
    val colors = MaterialTheme.colorScheme

    Terrain(tiles, colors)
    Water(tiles, colors)
    Roads(tiles, colors)
    Built(tiles, colors)
    Labels(tiles, colors)
  }

  @Composable
  private fun Terrain(tiles: Source, colors: ColorScheme) {
    BackgroundLayer(id = "material-background", color = const(colors.surfaceDim))

    FillLayer(
      id = "material-earth",
      source = tiles,
      sourceLayer = "earth",
      filter = isPolygon,
      color = const(colors.surface),
    )

    // Landcover stands in for land use at low zooms and fades out as the landuse layer fades in.
    FillLayer(
      id = "material-landcover",
      source = tiles,
      sourceLayer = "landcover",
      opacity = interpolate(linear(), zoom(), 5 to const(1f), 7 to const(0f)),
      color = const(colors.surfaceContainerLow),
    )

    // Protomaps Light assigns each land use a hue; this demo gives them all one tone.
    val landuseFade = interpolate(linear(), zoom(), 6 to const(0f), 11 to const(1f))

    FillLayer(
      id = "material-landuse",
      source = tiles,
      sourceLayer = "landuse",
      filter =
        isKind(
          "national_park",
          "park",
          "cemetery",
          "protected_area",
          "nature_reserve",
          "forest",
          "golf_course",
          "wood",
          "grass",
          "meadow",
          "zoo",
          "garden",
          "hospital",
          "school",
          "university",
          "college",
          "beach",
          "sand",
          "industrial",
          "military",
          "naval_base",
          "airfield",
          "aerodrome",
        ),
      opacity = landuseFade,
      color = const(colors.surfaceContainerLow),
    )

    FillLayer(
      id = "material-landuse-pedestrian",
      source = tiles,
      sourceLayer = "landuse",
      filter = isKind("pedestrian", "dam"),
      color = const(colors.surfaceContainerLow),
    )

    FillLayer(
      id = "material-landuse-pier",
      source = tiles,
      sourceLayer = "landuse",
      filter = kind eq const("pier"),
      color = const(colors.surfaceContainerHigh),
    )

    LineLayer(
      id = "material-roads-runway",
      source = tiles,
      sourceLayer = "roads",
      filter = kindDetail eq const("runway"),
      color = const(colors.surfaceContainer),
      width = roadWidth(10 to const(0.dp), 12 to const(4.dp), 18 to const(30.dp)),
    )

    LineLayer(
      id = "material-roads-taxiway",
      source = tiles,
      sourceLayer = "roads",
      minZoom = 13f,
      filter = kindDetail eq const("taxiway"),
      color = const(colors.surfaceContainer),
      width = roadWidth(13 to const(0.dp), 13.5 to const(1.dp), 15 to const(6.dp)),
    )
  }

  @Composable
  private fun Water(tiles: Source, colors: ColorScheme) {
    FillLayer(
      id = "material-water",
      source = tiles,
      sourceLayer = "water",
      filter = isPolygon,
      color = const(colors.secondaryContainer),
    )

    LineLayer(
      id = "material-water-river",
      source = tiles,
      sourceLayer = "water",
      minZoom = 9f,
      filter = isKind("river", "canal"),
      color = const(colors.secondaryContainer),
      width = roadWidth(9 to const(0.dp), 9.5 to const(1.dp), 18 to const(12.dp)),
    )

    LineLayer(
      id = "material-water-stream",
      source = tiles,
      sourceLayer = "water",
      minZoom = 14f,
      filter = isKind("stream", "ditch", "drain"),
      color = const(colors.secondaryContainer),
      width = const(0.5.dp),
    )
  }

  /**
   * Street shading after Protomaps White and Black: casings use the land color, and streets
   * contrast with the land in the direction opposite the buildings (see [Built]), which keeps
   * streets and blocks distinct without hue. The surface container ramp supplies that contrast in
   * both themes, darker than the land in light themes and lighter in dark ones.
   *
   * Protomaps draws tunnels and bridges as separate layer stacks; this demo draws one stack and
   * renders tunnels at half opacity.
   */
  @Composable
  private fun Roads(tiles: Source, colors: ColorScheme) {
    val sourceLayer = "roads"

    val highwayWidth =
      roadWidth(
        3 to const(0.dp),
        6 to const(1.1.dp),
        12 to const(1.6.dp),
        15 to const(5.dp),
        18 to const(15.dp),
      )
    val majorWidth =
      roadWidth(6 to const(0.dp), 12 to const(1.6.dp), 15 to const(3.dp), 18 to const(13.dp))
    val minorWidth =
      roadWidth(11 to const(0.dp), 12.5 to const(0.5.dp), 15 to const(2.dp), 18 to const(11.dp))
    val linkWidth = roadWidth(13 to const(0.dp), 13.5 to const(1.dp), 18 to const(11.dp))
    val casingWidth = roadWidth(7 to const(0.dp), 7.5 to const(1.dp))

    val isHighway = all(!isTunnel, !isLink, kind eq const("highway"))
    val isMajor = all(!isTunnel, !isLink, kind eq const("major_road"))
    val isMinor = all(!isTunnel, !isLink, kind eq const("minor_road"))
    val isService = all(isMinor, kindDetail eq const("service"))
    val isPath = all(!isTunnel, isKind("other", "path"), !(kindDetail eq const("pier")))
    val isSurfaceLink = all(!isTunnel, isLink)

    val roundCap = const(LineCap.Round)
    val roundJoin = const(LineJoin.Round)

    LineLayer(
      id = "material-roads-tunnels",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = all(isTunnel, isKind("highway", "major_road", "minor_road")),
      opacity = const(0.5f),
      color = const(colors.surfaceContainer),
      width = majorWidth,
    )

    // Casings: a line whose hollow center (gap width) matches the road drawn on top of it.
    LineLayer(
      id = "material-roads-casing-highway",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isHighway,
      color = const(colors.surface),
      gapWidth = highwayWidth,
      width = casingWidth,
    )
    LineLayer(
      id = "material-roads-casing-major",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isMajor,
      color = const(colors.surface),
      gapWidth = majorWidth,
      width = casingWidth,
    )
    LineLayer(
      id = "material-roads-casing-minor",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isMinor,
      color = const(colors.surface),
      gapWidth = minorWidth,
      width = roadWidth(12 to const(0.dp), 12.5 to const(1.dp)),
    )
    LineLayer(
      id = "material-roads-casing-link",
      source = tiles,
      sourceLayer = sourceLayer,
      minZoom = 13f,
      filter = isSurfaceLink,
      color = const(colors.surface),
      gapWidth = linkWidth,
      width = roadWidth(13 to const(0.dp), 13.5 to const(1.5.dp)),
    )

    LineLayer(
      id = "material-roads-path",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isPath,
      color = const(colors.surfaceContainer),
      width = roadWidth(14 to const(0.5.dp), 20 to const(12.dp)),
    )
    LineLayer(
      id = "material-roads-service",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isService,
      color = const(colors.surfaceContainer),
      width = roadWidth(13 to const(0.dp), 18 to const(8.dp)),
    )
    LineLayer(
      id = "material-roads-minor",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = all(isMinor, !(kindDetail eq const("service"))),
      cap = roundCap,
      join = roundJoin,
      color =
        interpolate(
          linear(),
          zoom(),
          11 to const(colors.surfaceContainerHigh),
          16 to const(colors.surfaceContainer),
        ),
      width = minorWidth,
    )
    LineLayer(
      id = "material-roads-link",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isSurfaceLink,
      cap = roundCap,
      join = roundJoin,
      color = const(colors.surfaceContainer),
      width = linkWidth,
    )
    LineLayer(
      id = "material-roads-major",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isMajor,
      cap = roundCap,
      join = roundJoin,
      color = const(colors.surfaceContainerHigh),
      width = majorWidth,
    )
    LineLayer(
      id = "material-roads-highway",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = isHighway,
      cap = roundCap,
      join = roundJoin,
      color = const(colors.surfaceContainerHighest),
      width = highwayWidth,
    )
    LineLayer(
      id = "material-roads-rail",
      source = tiles,
      sourceLayer = sourceLayer,
      filter = kind eq const("rail"),
      opacity = const(0.5f),
      color = const(colors.outline),
      dasharray = const(listOf(0.3f, 0.75f)),
      width = roadWidth(3 to const(0.dp), 6 to const(0.15.dp), 18 to const(9.dp)),
    )
  }

  @Composable
  private fun Built(tiles: Source, colors: ColorScheme) {
    // surfaceDim is darker than surface only in light schemes; in dark schemes the darkest tone is
    // surfaceContainerLowest.
    val buildingsColor = if (dark) colors.surfaceContainerLowest else colors.surfaceDim

    // With 3D buildings enabled, the flat layer covers the zooms below the extrusion's minimum.
    FillLayer(
      id = "material-buildings",
      source = tiles,
      sourceLayer = "buildings",
      maxZoom = if (extrudeBuildings) 14f else 24f,
      filter = isKind("building", "building_part"),
      opacity = const(0.5f),
      color = const(buildingsColor),
    )

    // Protomaps carries OSM heights; buildings without one stay flat under the extruded neighbors.
    FillExtrusionLayer(
      id = "material-buildings-3d",
      source = tiles,
      sourceLayer = "buildings",
      minZoom = 14f,
      filter = isKind("building", "building_part"),
      visible = extrudeBuildings,
      opacity = const(0.8f),
      color = const(buildingsColor),
      base = feature["min_height"].asNumber(const(0f)),
      height = feature["height"].asNumber(const(0f)),
    )

    LineLayer(
      id = "material-boundaries-region",
      source = tiles,
      sourceLayer = "boundaries",
      filter = feature["kind_detail"].asNumber() lte const(4f),
      opacity = const(0.6f),
      color = const(colors.outline),
      dasharray = const(listOf(2f, 2f)),
      width = const(0.4.dp),
    )
    LineLayer(
      id = "material-boundaries-country",
      source = tiles,
      sourceLayer = "boundaries",
      filter = feature["kind_detail"].asNumber() lte const(2f),
      opacity = const(0.8f),
      color = const(colors.outline),
      width = const(0.7.dp),
    )
  }

  @Composable
  private fun Labels(tiles: Source, colors: ColorScheme) {
    val regular = const(listOf("Noto Sans Regular"))
    val medium = const(listOf("Noto Sans Medium"))
    val italic = const(listOf("Noto Sans Italic"))

    SymbolLayer(
      id = "material-label-country",
      source = tiles,
      sourceLayer = "places",
      filter = kind eq const("country"),
      sortKey = feature["min_zoom"].asNumber(),
      textField = label,
      textFont = medium,
      textTransform = const(TextTransform.Uppercase),
      textLetterSpacing = const(0.1f.em),
      textSize =
        interpolate(linear(), zoom(), 2 to const(9.sp), 6 to const(12.sp), 8 to const(16.sp)),
      textColor = const(colors.onSurfaceVariant),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-region",
      source = tiles,
      sourceLayer = "places",
      filter = kind eq const("region"),
      textField = label,
      textFont = regular,
      textTransform = const(TextTransform.Uppercase),
      textLetterSpacing = const(0.1f.em),
      textSize = interpolate(linear(), zoom(), 3 to const(10.sp), 7 to const(14.sp)),
      textColor = const(colors.outline),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-locality",
      source = tiles,
      sourceLayer = "places",
      filter = kind eq const("locality"),
      sortKey = feature["min_zoom"].asNumber(),
      textField = label,
      textFont =
        switch(
          condition(feature["min_zoom"].asNumber() lte const(5f), medium),
          fallback = regular,
        ),
      textSize =
        interpolate(
          linear(),
          zoom(),
          2 to const(9.sp),
          5 to const(11.sp),
          10 to const(14.sp),
          16 to const(18.sp),
        ),
      textColor = const(colors.onSurface),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-subplace",
      source = tiles,
      sourceLayer = "places",
      minZoom = 11f,
      filter = isKind("neighbourhood", "macrohood"),
      textField = label,
      textFont = regular,
      textTransform = const(TextTransform.Uppercase),
      textLetterSpacing = const(0.15f.em),
      textMaxWidth = const(7f.em),
      textSize = interpolate(linear(), zoom(), 11 to const(9.sp), 14 to const(12.sp)),
      textColor = const(colors.onSurfaceVariant),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-ocean",
      source = tiles,
      sourceLayer = "water",
      filter = isKind("sea", "ocean", "bay", "strait", "fjord"),
      textField = label,
      textFont = italic,
      textTransform = const(TextTransform.Uppercase),
      textLetterSpacing = const(0.2f.em),
      textMaxWidth = const(9f.em),
      textSize = interpolate(linear(), zoom(), 3 to const(10.sp), 10 to const(14.sp)),
      textColor = const(colors.onSecondaryContainer),
      textHaloColor = const(colors.secondaryContainer),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-lake",
      source = tiles,
      sourceLayer = "water",
      filter = isKind("lake", "water", "reservoir"),
      textField = label,
      textFont = italic,
      textLetterSpacing = const(0.1f.em),
      textMaxWidth = const(9f.em),
      textSize = interpolate(linear(), zoom(), 3 to const(10.sp), 10 to const(12.sp)),
      textColor = const(colors.onSecondaryContainer),
      textHaloColor = const(colors.secondaryContainer),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-waterway",
      source = tiles,
      sourceLayer = "water",
      minZoom = 13f,
      filter = isKind("river", "stream", "canal"),
      placement = const(SymbolPlacement.Line),
      textField = label,
      textFont = italic,
      textLetterSpacing = const(0.2f.em),
      textSize = const(11.sp),
      textColor = const(colors.onSecondaryContainer),
      textHaloColor = const(colors.secondaryContainer),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-road-major",
      source = tiles,
      sourceLayer = "roads",
      minZoom = 11f,
      filter = isKind("highway", "major_road"),
      placement = const(SymbolPlacement.Line),
      sortKey = feature["min_zoom"].asNumber(),
      textField = label,
      textFont = regular,
      textSize = const(11.sp),
      textColor = const(colors.onSurfaceVariant),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-road-minor",
      source = tiles,
      sourceLayer = "roads",
      minZoom = 15f,
      filter = isKind("minor_road", "other", "path"),
      placement = const(SymbolPlacement.Line),
      sortKey = feature["min_zoom"].asNumber(),
      textField = label,
      textFont = regular,
      textSize = const(10.sp),
      textColor = const(colors.onSurfaceVariant),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-poi",
      source = tiles,
      sourceLayer = "pois",
      minZoom = 15f,
      filter =
        isKind(
          "park",
          "forest",
          "garden",
          "zoo",
          "beach",
          "peak",
          "station",
          "university",
          "hospital",
          "stadium",
          "museum",
          "library",
        ),
      textField = label,
      textFont = regular,
      textMaxWidth = const(8f.em),
      textSize = interpolate(linear(), zoom(), 15 to const(10.sp), 18 to const(12.sp)),
      textColor = const(colors.onSurfaceVariant),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )

    SymbolLayer(
      id = "material-label-address",
      source = tiles,
      sourceLayer = "buildings",
      minZoom = 17f,
      filter = kind eq const("address"),
      textField = feature["addr_housenumber"].asString(),
      textFont = italic,
      textSize = const(10.sp),
      textColor = const(colors.outline),
      textHaloColor = const(colors.surface),
      textHaloWidth = const(1.dp),
    )
  }

  private data class TokenUse(val token: String, val role: String, val color: Color)

  @Composable
  override fun Panel(state: DemoAppState) {
    SwitchRow(label = "Dark theme", checked = dark, onCheckedChange = { dark = it })
    SwitchRow(
      label = "3D buildings",
      checked = extrudeBuildings,
      onCheckedChange = { extrudeBuildings = it },
    )

    SectionHeader("Drawn from these tokens")
    val colors = MaterialTheme.colorScheme
    listOf(
        TokenUse("surface", "Land and street casings", colors.surface),
        TokenUse("surfaceDim", "Ocean backdrop", colors.surfaceDim),
        TokenUse("surfaceContainerLow", "Parks, campuses, districts", colors.surfaceContainerLow),
        TokenUse("secondaryContainer", "Water", colors.secondaryContainer),
        TokenUse("onSecondaryContainer", "Water labels", colors.onSecondaryContainer),
        TokenUse(
          "surfaceContainer … surfaceContainerHighest",
          "Streets, minor to motorway",
          colors.surfaceContainerHigh,
        ),
        TokenUse(
          if (dark) "surfaceContainerLowest" else "surfaceDim",
          "Buildings",
          if (dark) colors.surfaceContainerLowest else colors.surfaceDim,
        ),
        TokenUse("onSurface, onSurfaceVariant", "Place labels", colors.onSurface),
      )
      .forEach { TokenRow(it) }
  }

  @Composable
  private fun TokenRow(use: TokenUse) {
    ListItem(
      leadingContent = {
        Box(
          Modifier.size(24.dp)
            .clip(CircleShape)
            .background(use.color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
      },
      headlineContent = { Text(use.role) },
      supportingContent = { Text(use.token) },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
  }
}
