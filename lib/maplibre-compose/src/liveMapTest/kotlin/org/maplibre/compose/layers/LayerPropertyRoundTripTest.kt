package org.maplibre.compose.layers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.elevation
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.heatmapDensity
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.textVariableAnchorOffset
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.CirclePitchAlignment
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.HillshadeMethod
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.IconTextFit
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.SymbolOverlap
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.expressions.value.SymbolZOrder
import org.maplibre.compose.expressions.value.TextJustify
import org.maplibre.compose.expressions.value.TextPitchAlignment
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.expressions.value.TextTransform
import org.maplibre.compose.expressions.value.TextWritingMode
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.RasterDemEncoding
import org.maplibre.compose.sources.RasterDemSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.compose.testing.MapLibreFlavor
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.mapLibreFlavor
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

class LayerPropertyRoundTripTest {

  @Test
  fun background_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(BACKGROUND_CASES) { _ -> ({ id -> BackgroundLayer(id) }) }
  }

  @Test
  fun circle_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(CIRCLE_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> CircleLayer(id, source) })
    }
  }

  @Test
  fun fill_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(FILL_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> FillLayer(id, source) })
    }
  }

  @Test
  fun fill_extrusion_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(FILL_EXTRUSION_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> FillExtrusionLayer(id, source) })
    }
  }

  @Test
  fun heatmap_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(HEATMAP_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> HeatmapLayer(id, source) })
    }
  }

  @Test
  fun line_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(LINE_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> LineLayer(id, source) })
    }
  }

  @Test
  fun symbol_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(SYMBOL_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> SymbolLayer(id, source) })
    }
  }

  @Test
  fun raster_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(RASTER_CASES) { style ->
      val source =
        RasterSource(
          id = "raster",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(),
          tileSize = 256,
        )
      style.addSource(source)
      ({ id -> RasterLayer(id, source) })
    }
  }

  @Test
  fun hillshade_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(HILLSHADE_CASES) { style ->
      val source =
        RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(),
          tileSize = 256,
          demEncoding = RasterDemEncoding.Terrarium,
        )
      style.addSource(source)
      ({ id -> HillshadeLayer(id, source) })
    }
  }

  @Test
  fun color_relief_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(COLOR_RELIEF_CASES) { style ->
      val source =
        RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(),
          tileSize = 256,
          demEncoding = RasterDemEncoding.Terrarium,
        )
      style.addSource(source)
      ({ id -> ColorReliefLayer(id, source) })
    }
  }

  /**
   * @param prepare adds whatever sources the layer type needs and returns a factory for the layer.
   */
  private suspend fun <L : Layer> assertPropertiesRoundTrip(
    cases: List<Case<L>>,
    prepare: (Style) -> (String) -> L,
  ) {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style, "Errors: ${it.errors}")
      val makeLayer = prepare(style)

      val failures = buildList {
        cases.forEachIndexed { index, case ->
          addAll(check(style, makeLayer("pre-$index"), case, attachFirst = false))
          addAll(check(style, makeLayer("post-$index"), case, attachFirst = true))
        }
      }

      assertEquals(emptyList(), failures, "Properties did not round-trip through MapLibre")
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  private fun <L : Layer> check(
    style: Style,
    layer: L,
    case: Case<L>,
    attachFirst: Boolean,
  ): List<String> {
    val path = if (attachFirst) "after attach" else "before attach"
    try {
      if (attachFirst) {
        style.addLayer(layer)
        case.apply(layer)
      } else {
        case.apply(layer)
        style.addLayer(layer)
      }
    } catch (error: Throwable) {
      return listOf("${case.property} $path: MapLibre refused it: ${error.message}")
    }
    val actual = layer.binding.layerProperty(layer.id, case.property)
    val expected = Json.parseToJsonElement(case.expectedHere)
    return if (actual != null && actual.equivalentTo(expected)) emptyList()
    else listOf("${case.property} $path: expected $expected but MapLibre reports $actual")
  }

  /** Allows harmless floating-point round-off. */
  private fun JsonElement.equivalentTo(expected: JsonElement): Boolean =
    when {
      this is JsonPrimitive && expected is JsonPrimitive -> {
        val actualNumber = doubleOrNull
        val expectedNumber = expected.doubleOrNull
        if (actualNumber != null && expectedNumber != null) {
          abs(actualNumber - expectedNumber) <= NUMBER_TOLERANCE
        } else {
          this == expected
        }
      }
      this is JsonArray && expected is JsonArray ->
        size == expected.size &&
          zip(expected).all { (actual, wanted) -> actual.equivalentTo(wanted) }
      this is JsonObject && expected is JsonObject ->
        keys == expected.keys &&
          all { (key, actual) -> actual.equivalentTo(expected.getValue(key)) }
      else -> this == expected
    }

  /** @param glJs what MapLibre GL JS reports instead, where it differs. */
  private class Case<in L : Layer>(
    val property: String,
    val expected: String,
    val glJs: String? = null,
    val apply: (L) -> Unit,
  ) {
    val expectedHere: String
      get() = if (mapLibreFlavor == MapLibreFlavor.GL_JS) glJs ?: expected else expected
  }

  private companion object {
    const val NUMBER_TOLERANCE = 1e-5

    const val SOURCE_ID = "features"

    /** Unresolvable on purpose: tests must not reach the network. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"

    fun <T : ExpressionValue> Expression<T>.c() = compile(ExpressionContext.None)

    fun addFeatureSource(style: Style): Source =
      GeoJsonSource(
          id = SOURCE_ID,
          data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
          options = GeoJsonOptions(lineMetrics = true),
        )
        .also { style.addSource(it) }

    val BACKGROUND_CASES =
      listOf<Case<BackgroundLayer>>(
        Case("background-color", """["rgba",0.0,0.0,255.0,1.0]""", "\"rgba(0, 0, 255, 1)\"") {
          it.setBackgroundColor(const(Color.Blue).c())
        },
        Case("background-pattern", """["image","tile"]""") {
          it.setBackgroundPattern(image("tile").c())
        },
        Case("background-opacity", "0.5") { it.setBackgroundOpacity(const(0.5f).c()) },
      )

    val CIRCLE_CASES =
      listOf<Case<CircleLayer>>(
        Case("circle-sort-key", "2.0") { it.setCircleSortKey(const(2f).c()) },
        Case("circle-radius", "8.0") { it.setCircleRadius(const(8.dp).c()) },
        Case("circle-color", """["rgba",255.0,0.0,0.0,1.0]""", "\"rgba(255, 0, 0, 1)\"") {
          it.setCircleColor(const(Color.Red).c())
        },
        Case("circle-blur", "0.25") { it.setCircleBlur(const(0.25f).c()) },
        Case("circle-opacity", "0.5") { it.setCircleOpacity(const(0.5f).c()) },
        Case("circle-translate", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.setCircleTranslate(const(DpOffset(1.dp, 2.dp)).c())
        },
        Case("circle-translate-anchor", "\"viewport\"") {
          it.setCircleTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
        Case("circle-pitch-scale", "\"viewport\"") {
          it.setCirclePitchScale(const(CirclePitchScale.Viewport).c())
        },
        Case("circle-pitch-alignment", "\"map\"") {
          it.setCirclePitchAlignment(const(CirclePitchAlignment.Map).c())
        },
        Case("circle-stroke-width", "3.0") { it.setCircleStrokeWidth(const(3.dp).c()) },
        Case("circle-stroke-color", """["rgba",0.0,0.0,0.0,1.0]""", "\"rgba(0, 0, 0, 1)\"") {
          it.setCircleStrokeColor(const(Color.Black).c())
        },
        Case("circle-stroke-opacity", "0.75") { it.setCircleStrokeOpacity(const(0.75f).c()) },
      )

    val FILL_CASES =
      listOf<Case<FillLayer>>(
        Case("fill-sort-key", "2.0") { it.setFillSortKey(const(2f).c()) },
        Case("fill-antialias", "false") { it.setFillAntialias(const(false).c()) },
        Case("fill-opacity", "0.5") { it.setFillOpacity(const(0.5f).c()) },
        Case("fill-color", """["rgba",0.0,255.0,0.0,1.0]""", "\"rgba(0, 255, 0, 1)\"") {
          it.setFillColor(const(Color.Green).c())
        },
        Case("fill-outline-color", """["rgba",0.0,0.0,0.0,1.0]""", "\"rgba(0, 0, 0, 1)\"") {
          it.setFillOutlineColor(const(Color.Black).c())
        },
        Case("fill-translate", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.setFillTranslate(const(DpOffset(1.dp, 2.dp)).c())
        },
        Case("fill-translate-anchor", "\"viewport\"") {
          it.setFillTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
        Case("fill-pattern", """["image","brick"]""") { it.setFillPattern(image("brick").c()) },
      ) + glJsOnlyFillCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyFillCases(): List<Case<FillLayer>> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else listOf(Case("fill-layer-opacity", "0.4") { it.setFillLayerOpacity(const(0.4f).c()) })

    val FILL_EXTRUSION_CASES =
      listOf<Case<FillExtrusionLayer>>(
        Case("fill-extrusion-rounded-corner-distance", "10.0") {
          it.setFillExtrusionRoundedCornerDistance(const(10f).c())
        },
        Case("fill-extrusion-opacity", "0.5") { it.setFillExtrusionOpacity(const(0.5f).c()) },
        Case(
          "fill-extrusion-color",
          """["rgba",255.0,0.0,255.0,1.0]""",
          "\"rgba(255, 0, 255, 1)\"",
        ) {
          it.setFillExtrusionColor(const(Color.Magenta).c())
        },
        Case("fill-extrusion-translate", "[7.0,8.0]", """["literal",[7.0,8.0]]""") {
          it.setFillExtrusionTranslate(const(DpOffset(7.dp, 8.dp)).c())
        },
        Case("fill-extrusion-translate-anchor", "\"viewport\"") {
          it.setFillExtrusionTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
        Case("fill-extrusion-pattern", """["image","brick"]""") {
          it.setFillExtrusionPattern(image("brick").c())
        },
        Case("fill-extrusion-height", "30.0") { it.setFillExtrusionHeight(const(30f).c()) },
        Case("fill-extrusion-base", "5.0") { it.setFillExtrusionBase(const(5f).c()) },
        Case("fill-extrusion-vertical-gradient", "false") {
          it.setFillExtrusionVerticalGradient(const(false).c())
        },
      )

    val HEATMAP_CASES =
      listOf<Case<HeatmapLayer>>(
        Case("heatmap-radius", "12.0") { it.setHeatmapRadius(const(12.dp).c()) },
        Case("heatmap-weight", "0.5") { it.setHeatmapWeight(const(0.5f).c()) },
        Case("heatmap-intensity", "2.0") { it.setHeatmapIntensity(const(2f).c()) },
        // MapLibre rejects a constant here ("color ramp must be an expression") and accepts only
        // heatmap-density as the interpolation input.
        Case(
          "heatmap-color",
          """["interpolate",["linear"],["heatmap-density"],
             0.0,["rgba",0.0,0.0,255.0,1.0],1.0,["rgba",255.0,0.0,0.0,1.0]]""",
          """["interpolate",["linear"],["heatmap-density"],0.0,"rgba(0, 0, 255, 1)",1.0,"rgba(255, 0, 0, 1)"]""",
        ) {
          it.setHeatmapColor(
            interpolate(linear(), heatmapDensity(), 0f to const(Color.Blue), 1f to const(Color.Red))
              .c()
          )
        },
        Case("heatmap-opacity", "0.75") { it.setHeatmapOpacity(const(0.75f).c()) },
      )

    val LINE_CASES =
      listOf<Case<LineLayer>>(
        Case("line-cap", "\"round\"") { it.setLineCap(const(LineCap.Round).c()) },
        Case("line-join", "\"bevel\"") { it.setLineJoin(const(LineJoin.Bevel).c()) },
        Case("line-miter-limit", "1.5") { it.setLineMiterLimit(const(1.5f).c()) },
        Case("line-round-limit", "1.25") { it.setLineRoundLimit(const(1.25f).c()) },
        Case("line-sort-key", "2.0") { it.setLineSortKey(const(2f).c()) },
        Case("line-opacity", "0.5") { it.setLineOpacity(const(0.5f).c()) },
        Case("line-color", """["rgba",0.0,0.0,255.0,1.0]""", "\"rgba(0, 0, 255, 1)\"") {
          it.setLineColor(const(Color.Blue).c())
        },
        Case("line-translate", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.setLineTranslate(const(DpOffset(1.dp, 2.dp)).c())
        },
        Case("line-translate-anchor", "\"viewport\"") {
          it.setLineTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
        Case("line-width", "3.0") { it.setLineWidth(const(3.dp).c()) },
        Case("line-gap-width", "1.0") { it.setLineGapWidth(const(1.dp).c()) },
        Case("line-offset", "2.0") { it.setLineOffset(const(2.dp).c()) },
        Case("line-blur", "1.0") { it.setLineBlur(const(1.dp).c()) },
        Case("line-dasharray", "[2.0,4.0]", """["literal",[2.0,4.0]]""") {
          it.setLineDasharray(const(listOf(2, 4)).c())
        },
        Case("line-pattern", """["image","dash"]""") { it.setLinePattern(image("dash").c()) },
        // Like heatmap-color, a ramp rather than a constant, and only over line-progress.
        Case(
          "line-gradient",
          """["interpolate",["linear"],["line-progress"],
             0.0,["rgba",0.0,0.0,255.0,1.0],1.0,["rgba",255.0,0.0,0.0,1.0]]""",
          """["interpolate",["linear"],["line-progress"],0.0,"rgba(0, 0, 255, 1)",1.0,"rgba(255, 0, 0, 1)"]""",
        ) {
          it.setLineGradient(
            interpolate(
                linear(),
                Feature.lineProgress(),
                0f to const(Color.Blue),
                1f to const(Color.Red),
              )
              .c()
          )
        },
      ) + glJsOnlyLineCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyLineCases(): List<Case<LineLayer>> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else listOf(Case("line-layer-opacity", "0.4") { it.setLineLayerOpacity(const(0.4f).c()) })

    val RASTER_CASES =
      listOf<Case<RasterLayer>>(
        Case("raster-opacity", "0.5") { it.setRasterOpacity(const(0.5f).c()) },
        Case("raster-hue-rotate", "45.0") { it.setRasterHueRotate(const(45f).c()) },
        Case("raster-brightness-min", "0.25") { it.setRasterBrightnessMin(const(0.25f).c()) },
        Case("raster-brightness-max", "0.75") { it.setRasterBrightnessMax(const(0.75f).c()) },
        Case("raster-saturation", "0.5") { it.setRasterSaturation(const(0.5f).c()) },
        Case("raster-contrast", "0.25") { it.setRasterContrast(const(0.25f).c()) },
        Case("raster-resampling", "\"nearest\"") {
          it.setRasterResampling(const(RasterResampling.Nearest).c())
        },
        // Milliseconds.
        Case("raster-fade-duration", "250.0") {
          it.setRasterFadeDuration(const(250.milliseconds).c())
        },
      )

    val HILLSHADE_CASES =
      listOf<Case<HillshadeLayer>>(
        Case("hillshade-method", "\"igor\"") {
          it.setHillshadeMethod(const(HillshadeMethod.Igor).c())
        },
        // Reported inside an array: MapLibre Native's hillshade takes a list of light sources.
        Case("hillshade-illumination-direction", "[200.0]", "200.0") {
          it.setHillshadeIlluminationDirection(const(200f).c())
        },
        Case("hillshade-illumination-altitude", "[30.0]", "30.0") {
          it.setHillshadeIlluminationAltitude(const(30f).c())
        },
        // The multidirectional method takes one direction and altitude per light source.
        Case("hillshade-illumination-direction", "[210.0,300.0]", """["literal",[210.0,300.0]]""") {
          it.setHillshadeMethod(const(HillshadeMethod.Multidirectional).c())
          it.setHillshadeIlluminationDirection(const(listOf(210, 300)).c())
        },
        Case("hillshade-illumination-altitude", "[30.0,60.0]", """["literal",[30.0,60.0]]""") {
          it.setHillshadeMethod(const(HillshadeMethod.Multidirectional).c())
          it.setHillshadeIlluminationAltitude(const(listOf(30, 60)).c())
        },
        Case("hillshade-illumination-anchor", "\"map\"") {
          it.setHillshadeIlluminationAnchor(const(IlluminationAnchor.Map).c())
        },
        Case("hillshade-exaggeration", "0.5") { it.setHillshadeExaggeration(const(0.5f).c()) },
        Case("hillshade-shadow-color", """[["rgba",0.0,0.0,0.0,1.0]]""", "\"rgba(0, 0, 0, 1)\"") {
          it.setHillshadeShadowColor(const(Color.Black).c())
        },
        Case(
          "hillshade-highlight-color",
          """[["rgba",255.0,255.0,255.0,1.0]]""",
          "\"rgba(255, 255, 255, 1)\"",
        ) {
          it.setHillshadeHighlightColor(const(Color.White).c())
        },
        // Not a list: the accent colour is one colour however many lights there are.
        Case(
          "hillshade-accent-color",
          """["rgba",0.0,255.0,255.0,1.0]""",
          "\"rgba(0, 255, 255, 1)\"",
        ) {
          it.setHillshadeAccentColor(const(Color.Cyan).c())
        },
      ) + glJsOnlyHillshadeCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyHillshadeCases(): List<Case<HillshadeLayer>> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("resampling", "\"nearest\"") {
            it.setResampling(const(RasterResampling.Nearest).c())
          }
        )

    val COLOR_RELIEF_CASES =
      listOf<Case<ColorReliefLayer>>(
        // Like heatmap-color, a ramp rather than a constant, and only over elevation.
        Case(
          "color-relief-color",
          """["interpolate",["linear"],["elevation"],
             0.0,["rgba",0.0,0.0,255.0,1.0],3000.0,["rgba",255.0,0.0,0.0,1.0]]""",
          """["interpolate",["linear"],["elevation"],0.0,"rgba(0, 0, 255, 1)",3000.0,"rgba(255, 0, 0, 1)"]""",
        ) {
          it.setColorReliefColor(
            interpolate(linear(), elevation(), 0f to const(Color.Blue), 3000f to const(Color.Red))
              .c()
          )
        },
        Case("color-relief-opacity", "0.75") { it.setColorReliefOpacity(const(0.75f).c()) },
      ) + glJsOnlyColorReliefCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyColorReliefCases(): List<Case<ColorReliefLayer>> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("resampling", "\"nearest\"") {
            it.setResampling(const(RasterResampling.Nearest).c())
          }
        )

    val SYMBOL_CASES =
      listOf<Case<SymbolLayer>>(
        Case("symbol-placement", "\"line\"") {
          it.setSymbolPlacement(const(SymbolPlacement.Line).c())
        },
        Case("symbol-spacing", "30.0") { it.setSymbolSpacing(const(30.dp).c()) },
        Case("symbol-avoid-edges", "true") { it.setSymbolAvoidEdges(const(true).c()) },
        // Data-driven: MapLibre wraps it in the coercion the property's type implies.
        Case("symbol-sort-key", """["number",["get","rank"]]""", """["get","rank"]""") {
          it.setSymbolSortKey(Feature["rank"].cast<FloatValue>().c())
        },
        Case("symbol-z-order", "\"viewport-y\"") {
          it.setSymbolZOrder(const(SymbolZOrder.ViewportY).c())
        },
        Case("icon-allow-overlap", "true") { it.setIconAllowOverlap(const(true).c()) },
        Case("icon-ignore-placement", "true") { it.setIconIgnorePlacement(const(true).c()) },
        Case("icon-optional", "true") { it.setIconOptional(const(true).c()) },
        Case("icon-rotation-alignment", "\"map\"") {
          it.setIconRotationAlignment(const(IconRotationAlignment.Map).c())
        },
        Case("icon-size", "1.5") { it.setIconSize(const(1.5f).c()) },
        Case("icon-text-fit", "\"both\"") { it.setIconTextFit(const(IconTextFit.Both).c()) },
        // Style order is top, right, bottom, left, which is not the order DpPadding stores.
        Case("icon-text-fit-padding", "[2.0,3.0,4.0,1.0]", """["literal",[2.0,3.0,4.0,1.0]]""") {
          it.setIconTextFitPadding(const(DpPadding(1.dp, 2.dp, 3.dp, 4.dp)).c())
        },
        Case(
          "icon-text-fit-padding",
          "[-2.5,0.1,-7.1,2.5]",
          """["literal",[-2.5,0.1,-7.1,2.5]]""",
        ) {
          it.setIconTextFitPadding(const(DpPadding(2.5.dp, (-2.5).dp, 0.1.dp, (-7.1).dp)).c())
        },
        Case("icon-image", """["image","marker"]""") { it.setIconImage(image("marker").c()) },
        Case("icon-rotate", "45.0") { it.setIconRotate(const(45f).c()) },
        Case("icon-padding", "[2.0,3.0,4.0,1.0]", """["literal",[2.0,3.0,4.0,1.0]]""") {
          it.setIconPadding(const(DpPadding(1.dp, 2.dp, 3.dp, 4.dp)).c())
        },
        Case("icon-padding", "[-2.5,0.1,-7.1,2.5]", """["literal",[-2.5,0.1,-7.1,2.5]]""") {
          it.setIconPadding(const(DpPadding(2.5.dp, (-2.5).dp, 0.1.dp, (-7.1).dp)).c())
        },
        Case("icon-keep-upright", "true") { it.setIconKeepUpright(const(true).c()) },
        Case("icon-offset", "[3.0,4.0]", """["literal",[3.0,4.0]]""") {
          it.setIconOffset(const(DpOffset(3.dp, 4.dp)).c())
        },
        Case("icon-anchor", "\"bottom-left\"") {
          it.setIconAnchor(const(SymbolAnchor.BottomLeft).c())
        },
        Case("icon-pitch-alignment", "\"viewport\"") {
          it.setIconPitchAlignment(const(IconPitchAlignment.Viewport).c())
        },
        Case("icon-opacity", "0.25") { it.setIconOpacity(const(0.25f).c()) },
        Case("icon-color", """["rgba",255.0,0.0,0.0,1.0]""", "\"rgba(255, 0, 0, 1)\"") {
          it.setIconColor(const(Color.Red).c())
        },
        Case("icon-halo-color", """["rgba",0.0,0.0,255.0,1.0]""", "\"rgba(0, 0, 255, 1)\"") {
          it.setIconHaloColor(const(Color.Blue).c())
        },
        Case("icon-halo-width", "2.0") { it.setIconHaloWidth(const(2.dp).c()) },
        Case("icon-halo-blur", "1.0") { it.setIconHaloBlur(const(1.dp).c()) },
        Case("icon-translate", "[5.0,6.0]", """["literal",[5.0,6.0]]""") {
          it.setIconTranslate(const(DpOffset(5.dp, 6.dp)).c())
        },
        Case("icon-translate-anchor", "\"viewport\"") {
          it.setIconTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
        Case("text-pitch-alignment", "\"map\"") {
          it.setTextPitchAlignment(const(TextPitchAlignment.Map).c())
        },
        // Not `viewport-glyph`, which the style spec has and MapLibre Native does not implement.
        // See UnsupportedLayerPropertyTest for what happens to a caller who asks for it.
        Case("text-rotation-alignment", "\"viewport\"") {
          it.setTextRotationAlignment(const(TextRotationAlignment.Viewport).c())
        },
        // A `format` expression comes back as the sections object MapLibre parsed it into.
        Case(
          "text-field",
          """{"sections":[{"text":"Hello","fontStack":null,"textColor":null,"scale":null,
             "image":null}]}""",
          """["format","Hello",{}]""",
        ) {
          it.setTextField(format(span("Hello")).c())
        },
        Case("text-font", """["Noto Sans Regular"]""", """["literal",["Noto Sans Regular"]]""") {
          it.setTextFont(const(listOf("Noto Sans Regular")).c())
        },
        Case("text-size", "14.0") { it.setTextSize(const(14.dp).c()) },
        Case("text-max-width", "9.0") { it.setTextMaxWidth(const(9f).c()) },
        Case("text-line-height", "1.25") { it.setTextLineHeight(const(1.25f).c()) },
        Case("text-letter-spacing", "0.5") { it.setTextLetterSpacing(const(0.5f).c()) },
        Case("text-justify", "\"right\"") { it.setTextJustify(const(TextJustify.Right).c()) },
        Case("text-radial-offset", "1.5") { it.setTextRadialOffset(const(1.5f).c()) },
        Case("text-variable-anchor", """["top","bottom"]""", """["literal",["top","bottom"]]""") {
          it.setTextVariableAnchor(
            const(listOf(SymbolAnchor.Top, SymbolAnchor.Bottom)).cast<ListValue<SymbolAnchor>>().c()
          )
        },
        Case(
          "text-variable-anchor-offset",
          """["top",[0.0,1.0],"bottom",[0.0,-2.0]]""",
          """["literal",["top",[0.0,1.0],"bottom",[0.0,-2.0]]]""",
        ) {
          it.setTextVariableAnchorOffset(
            textVariableAnchorOffset(
                SymbolAnchor.Top to Offset(0f, 1f),
                SymbolAnchor.Bottom to Offset(0f, -2f),
              )
              .c()
          )
        },
        Case("text-anchor", "\"top-left\"") { it.setTextAnchor(const(SymbolAnchor.TopLeft).c()) },
        Case("text-max-angle", "30.0") { it.setTextMaxAngle(const(30f).c()) },
        Case("text-writing-mode", """["horizontal"]""", """["literal",["horizontal"]]""") {
          it.setTextWritingMode(
            const(listOf(TextWritingMode.Horizontal)).cast<ListValue<TextWritingMode>>().c()
          )
        },
        Case("text-rotate", "90.0") { it.setTextRotate(const(90f).c()) },
        Case("text-padding", "4.0") { it.setTextPadding(const(4.dp).c()) },
        Case("text-keep-upright", "false") { it.setTextKeepUpright(const(false).c()) },
        Case("text-transform", "\"uppercase\"") {
          it.setTextTransform(const(TextTransform.Uppercase).c())
        },
        Case("text-offset", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.setTextOffset(const(Offset(1f, 2f)).c())
        },
        Case("text-allow-overlap", "true") { it.setTextAllowOverlap(const(true).c()) },
        Case("text-ignore-placement", "true") { it.setTextIgnorePlacement(const(true).c()) },
        Case("text-optional", "true") { it.setTextOptional(const(true).c()) },
        Case("text-opacity", """["interpolate",["linear"],["zoom"],0.0,0.0,10.0,1.0]""") {
          it.setTextOpacity(
            interpolate(linear(), zoom(), 0f to const(0f), 10f to const(1f)).cast<FloatValue>().c()
          )
        },
        // MapLibre stores colours premultiplied as floats, so a fractional alpha comes back a
        // rounding step off the byte that was sent.
        Case(
          "text-color",
          """["rgba",17.0,34.0,51.0,0.5]""",
          "\"rgba(17, 34, 51, 0.5019607843137255)\"",
        ) {
          it.setTextColor(const(Color(0x80112233)).c())
        },
        Case(
          "text-halo-color",
          """["rgba",255.0,255.0,255.0,1.0]""",
          "\"rgba(255, 255, 255, 1)\"",
        ) {
          it.setTextHaloColor(const(Color.White).c())
        },
        Case("text-halo-width", "2.0") { it.setTextHaloWidth(const(2.dp).c()) },
        Case("text-halo-blur", "1.0") { it.setTextHaloBlur(const(1.dp).c()) },
        Case("text-translate", "[5.0,6.0]", """["literal",[5.0,6.0]]""") {
          it.setTextTranslate(const(DpOffset(5.dp, 6.dp)).c())
        },
        Case("text-translate-anchor", "\"viewport\"") {
          it.setTextTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
      ) + glJsOnlySymbolCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlySymbolCases(): List<Case<SymbolLayer>> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("icon-overlap", "\"cooperative\"") { it.setIconOverlap(const("cooperative").c()) },
          Case("text-overlap", "\"cooperative\"") {
            it.setTextOverlap(const(SymbolOverlap.Cooperative).c())
          },
        )
  }
}
