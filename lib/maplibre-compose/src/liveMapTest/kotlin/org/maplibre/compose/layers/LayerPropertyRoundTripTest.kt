package org.maplibre.compose.layers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import org.maplibre.compose.expressions.dsl.textOffset
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
import org.maplibre.compose.expressions.value.SymbolHeightAnchor
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
import org.maplibre.compose.sources.RasterDemTileSource
import org.maplibre.compose.sources.RasterTileSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LayerInstallation
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.install
import org.maplibre.compose.style.systemAnimatorDurationScale
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
    assertPropertiesRoundTrip(BACKGROUND_CASES) { _ -> ({ id -> TestLayer(id, "background") }) }
  }

  @Test
  fun circle_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(CIRCLE_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> TestLayer(id, "circle", source) })
    }
  }

  @Test
  fun fill_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(FILL_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> TestLayer(id, "fill", source) })
    }
  }

  @Test
  fun fill_extrusion_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(FILL_EXTRUSION_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> TestLayer(id, "fill-extrusion", source) })
    }
  }

  @Test
  fun heatmap_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(HEATMAP_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> TestLayer(id, "heatmap", source) })
    }
  }

  @Test
  fun line_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(LINE_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> TestLayer(id, "line", source) })
    }
  }

  @Test
  fun symbol_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(SYMBOL_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> TestLayer(id, "symbol", source) })
    }
  }

  @Test
  fun raster_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(RASTER_CASES) { style ->
      val source =
        RasterTileSource(
          id = "raster",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(),
          tileSize = 256,
        )
      style.install(source)
      ({ id -> TestLayer(id, "raster", source) })
    }
  }

  @Test
  fun hillshade_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(HILLSHADE_CASES) { style ->
      val source =
        RasterDemTileSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(),
          tileSize = 256,
          demEncoding = RasterDemEncoding.Terrarium,
        )
      style.install(source)
      ({ id -> TestLayer(id, "hillshade", source) })
    }
  }

  @Test
  fun color_relief_layer_properties_reach_maplibre(): MapTestResult = runMapTest {
    assertPropertiesRoundTrip(COLOR_RELIEF_CASES) { style ->
      val source =
        RasterDemTileSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(),
          tileSize = 256,
          demEncoding = RasterDemEncoding.Terrarium,
        )
      style.install(source)
      ({ id -> TestLayer(id, "color-relief", source) })
    }
  }

  /**
   * A transition that goes away is pushed as the spec's empty object, because MapLibre Native
   * rejects a null one and keeps the previous timing. Neither engine still reports the written
   * timing afterwards: native reports nothing at all and MapLibre GL JS reports an empty object.
   */
  @Test
  fun a_cleared_transition_returns_a_layer_to_the_global_transition(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style, "Errors: ${it.errors}")

      val layer = TestLayer("timed", "background")
      layer.paint("background-color", (const(Color.Blue).c()).asLayerProperty())
      layer.paintTransition(
        "background-color",
        TransitionOptions(700.milliseconds, 50.milliseconds),
      )
      val installation = LayerInstallation(style, layer.definition(), beforeLayerId = "", scale)

      val written = assertNotNull(style.layerProperty("timed", "background-color-transition"))
      assertTrue(
        written.equivalentTo(Json.parseToJsonElement(scaledTransitionJson(700.0, 50.0))),
        "the engine should report the written timing, but reports $written",
      )

      layer.paintTransition("background-color", null)
      installation.update(layer.definition(), scale)

      val cleared = style.layerProperty("timed", "background-color-transition")
      assertTrue(
        cleared == null || cleared == JsonObject(emptyMap()),
        "clearing must remove both duration and delay, got $cleared",
      )
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  /**
   * @param prepare adds whatever sources the layer type needs and returns a factory for the layer.
   */
  private suspend fun assertPropertiesRoundTrip(
    cases: List<Case>,
    prepare: (StyleBinding) -> (String) -> TestLayer,
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

  private suspend fun check(
    style: StyleBinding,
    layer: TestLayer,
    case: Case,
    attachFirst: Boolean,
  ): List<String> {
    val path = if (attachFirst) "after attach" else "before attach"
    try {
      if (attachFirst) {
        val handle = LayerInstallation(style, layer.definition(), beforeLayerId = "", scale)
        case.apply(layer)
        handle.update(layer.definition(), scale)
      } else {
        case.apply(layer)
        LayerInstallation(style, layer.definition(), beforeLayerId = "", scale)
      }
    } catch (error: Throwable) {
      return listOf("${case.property} $path: MapLibre refused it: ${error.message}")
    }
    val actual = style.layerProperty(layer.id, case.property)
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
        if (!isString && !expected.isString && actualNumber != null && expectedNumber != null) {
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
  private class Case(
    val property: String,
    val expected: String,
    val glJs: String? = null,
    val apply: (TestLayer) -> Unit,
  ) {
    val expectedHere: String
      get() = if (mapLibreFlavor == MapLibreFlavor.GL_JS) glJs ?: expected else expected
  }

  private companion object {
    const val NUMBER_TOLERANCE = 1e-5

    const val SOURCE_ID = "features"

    /** Unresolvable on purpose: tests must not reach the network. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"

    fun <T : ExpressionValue?> Expression<T>.c() = compile(ExpressionContext.None)

    fun addFeatureSource(style: StyleBinding): GeoJsonSource =
      GeoJsonSource(
          id = SOURCE_ID,
          data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
          options = GeoJsonOptions(lineMetrics = true),
        )
        .also { style.install(it) }

    val BACKGROUND_CASES =
      listOf<Case>(
        Case("background-color", """["rgba",0.0,0.0,255.0,1.0]""", "\"rgba(0, 0, 255, 1)\"") {
          it.paint("background-color", (const(Color.Blue).c()).asLayerProperty())
        },
        Case("background-pattern", """["image","tile"]""") {
          it.paint("background-pattern", (image("tile").c()).asLayerProperty())
        },
        Case("background-opacity", "0.5") {
          it.paint("background-opacity", (const(0.5f).c()).asLayerProperty())
        },
        Case("background-color-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("background-color", (const(Color.Blue).c()).asLayerProperty())
          it.paintTransition(
            "background-color",
            TransitionOptions(700.milliseconds, 50.milliseconds),
          )
        },
      )

    val CIRCLE_CASES =
      listOf<Case>(
        Case("circle-sort-key", "2.0") {
          it.layout("circle-sort-key", (const(2f).c()).asLayerProperty())
        },
        Case("circle-radius", "8.0") {
          it.paint("circle-radius", (const(8.dp).c()).asLayerProperty())
        },
        Case("circle-color", """["rgba",255.0,0.0,0.0,1.0]""", "\"rgba(255, 0, 0, 1)\"") {
          it.paint("circle-color", (const(Color.Red).c()).asLayerProperty())
        },
        Case("circle-blur", "0.25") {
          it.paint("circle-blur", (const(0.25f).c()).asLayerProperty())
        },
        Case("circle-opacity", "0.5") {
          it.paint("circle-opacity", (const(0.5f).c()).asLayerProperty())
        },
        Case("circle-translate", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.paint("circle-translate", (const(DpOffset(1.dp, 2.dp)).c()).asLayerProperty())
        },
        Case("circle-translate-anchor", "\"viewport\"") {
          it.paint(
            "circle-translate-anchor",
            (const(TranslateAnchor.Viewport).c()).asLayerProperty(),
          )
        },
        Case("circle-pitch-scale", "\"viewport\"") {
          it.paint("circle-pitch-scale", (const(CirclePitchScale.Viewport).c()).asLayerProperty())
        },
        Case("circle-pitch-alignment", "\"map\"") {
          it.paint(
            "circle-pitch-alignment",
            (const(CirclePitchAlignment.Map).c()).asLayerProperty(),
          )
        },
        Case("circle-stroke-width", "3.0") {
          it.paint("circle-stroke-width", (const(3.dp).c()).asLayerProperty())
        },
        Case("circle-stroke-color", """["rgba",0.0,0.0,0.0,1.0]""", "\"rgba(0, 0, 0, 1)\"") {
          it.paint("circle-stroke-color", (const(Color.Black).c()).asLayerProperty())
        },
        Case("circle-stroke-opacity", "0.75") {
          it.paint("circle-stroke-opacity", (const(0.75f).c()).asLayerProperty())
        },
        Case("circle-color-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("circle-color", (const(Color.Red).c()).asLayerProperty())
          it.paintTransition("circle-color", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
      )

    val FILL_CASES =
      listOf<Case>(
        Case("fill-sort-key", "2.0") {
          it.layout("fill-sort-key", (const(2f).c()).asLayerProperty())
        },
        Case("fill-antialias", "false") {
          it.paint("fill-antialias", (const(false).c()).asLayerProperty())
        },
        Case("fill-opacity", "0.5") {
          it.paint("fill-opacity", (const(0.5f).c()).asLayerProperty())
        },
        Case("fill-color", """["rgba",0.0,255.0,0.0,1.0]""", "\"rgba(0, 255, 0, 1)\"") {
          it.paint("fill-color", (const(Color.Green).c()).asLayerProperty())
        },
        Case("fill-outline-color", """["rgba",0.0,0.0,0.0,1.0]""", "\"rgba(0, 0, 0, 1)\"") {
          it.paint("fill-outline-color", (const(Color.Black).c()).asLayerProperty())
        },
        Case("fill-translate", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.paint("fill-translate", (const(DpOffset(1.dp, 2.dp)).c()).asLayerProperty())
        },
        Case("fill-translate-anchor", "\"viewport\"") {
          it.paint("fill-translate-anchor", (const(TranslateAnchor.Viewport).c()).asLayerProperty())
        },
        Case("fill-pattern", """["image","brick"]""") {
          it.paint("fill-pattern", (image("brick").c()).asLayerProperty())
        },
        Case("fill-color-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("fill-color", (const(Color.Red).c()).asLayerProperty())
          it.paintTransition("fill-color", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
        Case("fill-pattern-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("fill-pattern", (image("brick").c()).asLayerProperty())
          it.paintTransition("fill-pattern", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
      ) + glJsOnlyFillCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyFillCases(): List<Case> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("fill-layer-opacity", "0.4") {
            it.paint("fill-layer-opacity", (const(0.4f).c()).asLayerProperty())
          }
        )

    val FILL_EXTRUSION_CASES =
      listOf<Case>(
        Case("fill-extrusion-rounded-corner-distance", "10.0") {
          it.layout("fill-extrusion-rounded-corner-distance", (const(10f).c()).asLayerProperty())
        },
        Case("fill-extrusion-opacity", "0.5") {
          it.paint("fill-extrusion-opacity", (const(0.5f).c()).asLayerProperty())
        },
        Case(
          "fill-extrusion-color",
          """["rgba",255.0,0.0,255.0,1.0]""",
          "\"rgba(255, 0, 255, 1)\"",
        ) {
          it.paint("fill-extrusion-color", (const(Color.Magenta).c()).asLayerProperty())
        },
        Case("fill-extrusion-translate", "[7.0,8.0]", """["literal",[7.0,8.0]]""") {
          it.paint("fill-extrusion-translate", (const(DpOffset(7.dp, 8.dp)).c()).asLayerProperty())
        },
        Case("fill-extrusion-translate-anchor", "\"viewport\"") {
          it.paint(
            "fill-extrusion-translate-anchor",
            (const(TranslateAnchor.Viewport).c()).asLayerProperty(),
          )
        },
        Case("fill-extrusion-pattern", """["image","brick"]""") {
          it.paint("fill-extrusion-pattern", (image("brick").c()).asLayerProperty())
        },
        Case("fill-extrusion-height", "30.0") {
          it.paint("fill-extrusion-height", (const(30f).c()).asLayerProperty())
        },
        Case("fill-extrusion-base", "5.0") {
          it.paint("fill-extrusion-base", (const(5f).c()).asLayerProperty())
        },
        Case("fill-extrusion-vertical-gradient", "false") {
          it.paint("fill-extrusion-vertical-gradient", (const(false).c()).asLayerProperty())
        },
        Case("fill-extrusion-height-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("fill-extrusion-height", (const(30f).c()).asLayerProperty())
          it.paintTransition(
            "fill-extrusion-height",
            TransitionOptions(700.milliseconds, 50.milliseconds),
          )
        },
      )

    val HEATMAP_CASES =
      listOf<Case>(
        Case("heatmap-radius", "12.0") {
          it.paint("heatmap-radius", (const(12.dp).c()).asLayerProperty())
        },
        Case("heatmap-weight", "0.5") {
          it.paint("heatmap-weight", (const(0.5f).c()).asLayerProperty())
        },
        Case("heatmap-intensity", "2.0") {
          it.paint("heatmap-intensity", (const(2f).c()).asLayerProperty())
        },
        // MapLibre rejects a constant here ("color ramp must be an expression") and accepts only
        // heatmap-density as the interpolation input.
        Case(
          "heatmap-color",
          """["interpolate",["linear"],["heatmap-density"],
             0.0,["rgba",0.0,0.0,255.0,1.0],1.0,["rgba",255.0,0.0,0.0,1.0]]""",
          """["interpolate",["linear"],["heatmap-density"],0.0,"rgba(0, 0, 255, 1)",1.0,"rgba(255, 0, 0, 1)"]""",
        ) {
          it.paint(
            "heatmap-color",
            (interpolate(
                  linear(),
                  heatmapDensity(),
                  0f to const(Color.Blue),
                  1f to const(Color.Red),
                )
                .c())
              .asLayerProperty(),
          )
        },
        Case("heatmap-opacity", "0.75") {
          it.paint("heatmap-opacity", (const(0.75f).c()).asLayerProperty())
        },
        Case("heatmap-radius-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("heatmap-radius", (const(12.dp).c()).asLayerProperty())
          it.paintTransition("heatmap-radius", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
      )

    val LINE_CASES =
      listOf<Case>(
        Case("line-cap", "\"round\"") {
          it.layout("line-cap", (const(LineCap.Round).c()).asLayerProperty())
        },
        Case("line-join", "\"bevel\"") {
          it.layout("line-join", (const(LineJoin.Bevel).c()).asLayerProperty())
        },
        Case("line-miter-limit", "1.5") {
          it.layout("line-miter-limit", (const(1.5f).c()).asLayerProperty())
        },
        Case("line-round-limit", "1.25") {
          it.layout("line-round-limit", (const(1.25f).c()).asLayerProperty())
        },
        Case("line-sort-key", "2.0") {
          it.layout("line-sort-key", (const(2f).c()).asLayerProperty())
        },
        Case("line-opacity", "0.5") {
          it.paint("line-opacity", (const(0.5f).c()).asLayerProperty())
        },
        Case("line-color", """["rgba",0.0,0.0,255.0,1.0]""", "\"rgba(0, 0, 255, 1)\"") {
          it.paint("line-color", (const(Color.Blue).c()).asLayerProperty())
        },
        Case("line-translate", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.paint("line-translate", (const(DpOffset(1.dp, 2.dp)).c()).asLayerProperty())
        },
        Case("line-translate-anchor", "\"viewport\"") {
          it.paint("line-translate-anchor", (const(TranslateAnchor.Viewport).c()).asLayerProperty())
        },
        Case("line-width", "3.0") { it.paint("line-width", (const(3.dp).c()).asLayerProperty()) },
        Case("line-gap-width", "1.0") {
          it.paint("line-gap-width", (const(1.dp).c()).asLayerProperty())
        },
        Case("line-offset", "2.0") { it.paint("line-offset", (const(2.dp).c()).asLayerProperty()) },
        Case("line-blur", "1.0") { it.paint("line-blur", (const(1.dp).c()).asLayerProperty()) },
        Case("line-dasharray", "[2.0,4.0]", """["literal",[2.0,4.0]]""") {
          it.paint("line-dasharray", (const(listOf(2, 4)).c()).asLayerProperty())
        },
        Case("line-pattern", """["image","dash"]""") {
          it.paint("line-pattern", (image("dash").c()).asLayerProperty())
        },
        // Like heatmap-color, a ramp rather than a constant, and only over line-progress.
        Case(
          "line-gradient",
          """["interpolate",["linear"],["line-progress"],
             0.0,["rgba",0.0,0.0,255.0,1.0],1.0,["rgba",255.0,0.0,0.0,1.0]]""",
          """["interpolate",["linear"],["line-progress"],0.0,"rgba(0, 0, 255, 1)",1.0,"rgba(255, 0, 0, 1)"]""",
        ) {
          it.paint(
            "line-gradient",
            (interpolate(
                  linear(),
                  Feature.lineProgress(),
                  0f to const(Color.Blue),
                  1f to const(Color.Red),
                )
                .c())
              .asLayerProperty(),
          )
        },
        Case("line-width-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("line-width", (const(3.dp).c()).asLayerProperty())
          it.paintTransition("line-width", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
        Case("line-dasharray-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paintTransition("line-dasharray", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
      ) + glJsOnlyLineCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyLineCases(): List<Case> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("line-layer-opacity", "0.4") {
            it.paint("line-layer-opacity", (const(0.4f).c()).asLayerProperty())
          }
        )

    val RASTER_CASES =
      listOf<Case>(
        Case("raster-opacity", "0.5") {
          it.paint("raster-opacity", (const(0.5f).c()).asLayerProperty())
        },
        Case("raster-hue-rotate", "45.0") {
          it.paint("raster-hue-rotate", (const(45f).c()).asLayerProperty())
        },
        Case("raster-brightness-min", "0.25") {
          it.paint("raster-brightness-min", (const(0.25f).c()).asLayerProperty())
        },
        Case("raster-brightness-max", "0.75") {
          it.paint("raster-brightness-max", (const(0.75f).c()).asLayerProperty())
        },
        Case("raster-saturation", "0.5") {
          it.paint("raster-saturation", (const(0.5f).c()).asLayerProperty())
        },
        Case("raster-contrast", "0.25") {
          it.paint("raster-contrast", (const(0.25f).c()).asLayerProperty())
        },
        Case("raster-resampling", "\"nearest\"") {
          it.paint("raster-resampling", (const(RasterResampling.Nearest).c()).asLayerProperty())
        },
        // Milliseconds.
        Case("raster-fade-duration", "250.0") {
          it.paint("raster-fade-duration", (const(250.milliseconds).c()).asLayerProperty())
        },
        Case("raster-opacity-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("raster-opacity", (const(0.5f).c()).asLayerProperty())
          it.paintTransition("raster-opacity", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
      )

    val HILLSHADE_CASES =
      listOf<Case>(
        Case("hillshade-method", "\"igor\"") {
          it.paint("hillshade-method", (const(HillshadeMethod.Igor).c()).asLayerProperty())
        },
        // Reported inside an array: MapLibre Native's hillshade takes a list of light sources.
        Case("hillshade-illumination-direction", "[200.0]", "200.0") {
          it.paint("hillshade-illumination-direction", (const(200f).c()).asLayerProperty())
        },
        Case("hillshade-illumination-altitude", "[30.0]", "30.0") {
          it.paint("hillshade-illumination-altitude", (const(30f).c()).asLayerProperty())
        },
        // The multidirectional method takes one direction and altitude per light source.
        Case("hillshade-illumination-direction", "[210.0,300.0]", """["literal",[210.0,300.0]]""") {
          it.paint(
            "hillshade-method",
            (const(HillshadeMethod.Multidirectional).c()).asLayerProperty(),
          )
          it.paint(
            "hillshade-illumination-direction",
            (const(listOf(210, 300)).c()).asLayerProperty(),
          )
        },
        Case("hillshade-illumination-altitude", "[30.0,60.0]", """["literal",[30.0,60.0]]""") {
          it.paint(
            "hillshade-method",
            (const(HillshadeMethod.Multidirectional).c()).asLayerProperty(),
          )
          it.paint("hillshade-illumination-altitude", (const(listOf(30, 60)).c()).asLayerProperty())
        },
        Case("hillshade-illumination-anchor", "\"map\"") {
          it.paint(
            "hillshade-illumination-anchor",
            (const(IlluminationAnchor.Map).c()).asLayerProperty(),
          )
        },
        Case("hillshade-exaggeration", "0.5") {
          it.paint("hillshade-exaggeration", (const(0.5f).c()).asLayerProperty())
        },
        Case("hillshade-shadow-color", """[["rgba",0.0,0.0,0.0,1.0]]""", "\"rgba(0, 0, 0, 1)\"") {
          it.paint("hillshade-shadow-color", (const(Color.Black).c()).asLayerProperty())
        },
        Case(
          "hillshade-highlight-color",
          """[["rgba",255.0,255.0,255.0,1.0]]""",
          "\"rgba(255, 255, 255, 1)\"",
        ) {
          it.paint("hillshade-highlight-color", (const(Color.White).c()).asLayerProperty())
        },
        // Not a list: the accent colour is one colour however many lights there are.
        Case(
          "hillshade-accent-color",
          """["rgba",0.0,255.0,255.0,1.0]""",
          "\"rgba(0, 255, 255, 1)\"",
        ) {
          it.paint("hillshade-accent-color", (const(Color.Cyan).c()).asLayerProperty())
        },
        Case("hillshade-exaggeration-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("hillshade-exaggeration", (const(0.5f).c()).asLayerProperty())
          it.paintTransition(
            "hillshade-exaggeration",
            TransitionOptions(700.milliseconds, 50.milliseconds),
          )
        },
      ) + glJsOnlyHillshadeCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyHillshadeCases(): List<Case> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("resampling", "\"nearest\"") {
            it.paint("resampling", (const(RasterResampling.Nearest).c()).asLayerProperty())
          }
        )

    val COLOR_RELIEF_CASES =
      listOf<Case>(
        // Like heatmap-color, a ramp rather than a constant, and only over elevation.
        Case(
          "color-relief-color",
          """["interpolate",["linear"],["elevation"],
             0.0,["rgba",0.0,0.0,255.0,1.0],3000.0,["rgba",255.0,0.0,0.0,1.0]]""",
          """["interpolate",["linear"],["elevation"],0.0,"rgba(0, 0, 255, 1)",3000.0,"rgba(255, 0, 0, 1)"]""",
        ) {
          it.paint(
            "color-relief-color",
            (interpolate(linear(), elevation(), 0f to const(Color.Blue), 3000f to const(Color.Red))
                .c())
              .asLayerProperty(),
          )
        },
        Case("color-relief-opacity", "0.75") {
          it.paint("color-relief-opacity", (const(0.75f).c()).asLayerProperty())
        },
        Case("color-relief-opacity-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("color-relief-opacity", (const(0.75f).c()).asLayerProperty())
          it.paintTransition(
            "color-relief-opacity",
            TransitionOptions(700.milliseconds, 50.milliseconds),
          )
        },
      ) + glJsOnlyColorReliefCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlyColorReliefCases(): List<Case> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("resampling", "\"nearest\"") {
            it.paint("resampling", (const(RasterResampling.Nearest).c()).asLayerProperty())
          }
        )

    val SYMBOL_CASES =
      listOf<Case>(
        Case("symbol-placement", "\"line\"") {
          it.layout("symbol-placement", (const(SymbolPlacement.Line).c()).asLayerProperty())
        },
        Case("symbol-spacing", "30.0") {
          it.layout("symbol-spacing", (const(30.dp).c()).asLayerProperty())
        },
        Case("symbol-avoid-edges", "true") {
          it.layout("symbol-avoid-edges", (const(true).c()).asLayerProperty())
        },
        // Data-driven: MapLibre wraps it in the coercion the property's type implies.
        Case("symbol-sort-key", """["number",["get","rank"]]""", """["get","rank"]""") {
          it.layout("symbol-sort-key", (Feature["rank"].cast<FloatValue>().c()).asLayerProperty())
        },
        Case("symbol-z-order", "\"viewport-y\"") {
          it.layout("symbol-z-order", (const(SymbolZOrder.ViewportY).c()).asLayerProperty())
        },
        Case("icon-allow-overlap", "true") {
          it.layout("icon-allow-overlap", (const(true).c()).asLayerProperty())
        },
        Case("icon-ignore-placement", "true") {
          it.layout("icon-ignore-placement", (const(true).c()).asLayerProperty())
        },
        Case("icon-optional", "true") {
          it.layout("icon-optional", (const(true).c()).asLayerProperty())
        },
        Case("icon-rotation-alignment", "\"map\"") {
          it.layout(
            "icon-rotation-alignment",
            (const(IconRotationAlignment.Map).c()).asLayerProperty(),
          )
        },
        Case("icon-size", "1.5") { it.layout("icon-size", (const(1.5f).c()).asLayerProperty()) },
        Case("icon-text-fit", "\"both\"") {
          it.layout("icon-text-fit", (const(IconTextFit.Both).c()).asLayerProperty())
        },
        // Style-spec order is top, right, bottom, left, which is not the order DpPadding stores.
        Case("icon-text-fit-padding", "[2.0,3.0,4.0,1.0]", """["literal",[2.0,3.0,4.0,1.0]]""") {
          it.layout(
            "icon-text-fit-padding",
            (const(DpPadding(1.dp, 2.dp, 3.dp, 4.dp)).c()).asLayerProperty(),
          )
        },
        Case(
          "icon-text-fit-padding",
          "[-2.5,0.1,-7.1,2.5]",
          """["literal",[-2.5,0.1,-7.1,2.5]]""",
        ) {
          it.layout(
            "icon-text-fit-padding",
            (const(DpPadding(2.5.dp, (-2.5).dp, 0.1.dp, (-7.1).dp)).c()).asLayerProperty(),
          )
        },
        Case("icon-image", """["image","marker"]""") {
          it.layout("icon-image", (image("marker").c()).asLayerProperty())
        },
        Case("icon-rotate", "45.0") {
          it.layout("icon-rotate", (const(45f).c()).asLayerProperty())
        },
        Case("icon-padding", "[2.0,3.0,4.0,1.0]", """["literal",[2.0,3.0,4.0,1.0]]""") {
          it.layout(
            "icon-padding",
            (const(DpPadding(1.dp, 2.dp, 3.dp, 4.dp)).c()).asLayerProperty(),
          )
        },
        Case("icon-padding", "[-2.5,0.1,-7.1,2.5]", """["literal",[-2.5,0.1,-7.1,2.5]]""") {
          it.layout(
            "icon-padding",
            (const(DpPadding(2.5.dp, (-2.5).dp, 0.1.dp, (-7.1).dp)).c()).asLayerProperty(),
          )
        },
        Case("icon-keep-upright", "true") {
          it.layout("icon-keep-upright", (const(true).c()).asLayerProperty())
        },
        Case("icon-offset", "[3.0,4.0]", """["literal",[3.0,4.0]]""") {
          it.layout("icon-offset", (const(DpOffset(3.dp, 4.dp)).c()).asLayerProperty())
        },
        Case("icon-anchor", "\"bottom-left\"") {
          it.layout("icon-anchor", (const(SymbolAnchor.BottomLeft).c()).asLayerProperty())
        },
        Case("icon-pitch-alignment", "\"viewport\"") {
          it.layout(
            "icon-pitch-alignment",
            (const(IconPitchAlignment.Viewport).c()).asLayerProperty(),
          )
        },
        Case("icon-opacity", "0.25") {
          it.paint("icon-opacity", (const(0.25f).c()).asLayerProperty())
        },
        Case("icon-color", """["rgba",255.0,0.0,0.0,1.0]""", "\"rgba(255, 0, 0, 1)\"") {
          it.paint("icon-color", (const(Color.Red).c()).asLayerProperty())
        },
        Case("icon-halo-color", """["rgba",0.0,0.0,255.0,1.0]""", "\"rgba(0, 0, 255, 1)\"") {
          it.paint("icon-halo-color", (const(Color.Blue).c()).asLayerProperty())
        },
        Case("icon-halo-width", "2.0") {
          it.paint("icon-halo-width", (const(2.dp).c()).asLayerProperty())
        },
        Case("icon-halo-blur", "1.0") {
          it.paint("icon-halo-blur", (const(1.dp).c()).asLayerProperty())
        },
        Case("icon-translate", "[5.0,6.0]", """["literal",[5.0,6.0]]""") {
          it.paint("icon-translate", (const(DpOffset(5.dp, 6.dp)).c()).asLayerProperty())
        },
        Case("icon-translate-anchor", "\"viewport\"") {
          it.paint("icon-translate-anchor", (const(TranslateAnchor.Viewport).c()).asLayerProperty())
        },
        Case("text-pitch-alignment", "\"map\"") {
          it.layout("text-pitch-alignment", (const(TextPitchAlignment.Map).c()).asLayerProperty())
        },
        // Not `viewport-glyph`, which the style spec has and MapLibre Native does not implement.
        // See UnsupportedLayerPropertyTest for what happens to a caller who asks for it.
        Case("text-rotation-alignment", "\"viewport\"") {
          it.layout(
            "text-rotation-alignment",
            (const(TextRotationAlignment.Viewport).c()).asLayerProperty(),
          )
        },
        // A `format` expression comes back as the sections object MapLibre parsed it into.
        Case(
          "text-field",
          """{"sections":[{"text":"Hello","fontStack":null,"textColor":null,"scale":null,
             "image":null}]}""",
          """["format","Hello",{}]""",
        ) {
          it.layout("text-field", (format(span("Hello")).c()).asLayerProperty())
        },
        Case("text-font", """["Noto Sans Regular"]""", """["literal",["Noto Sans Regular"]]""") {
          it.layout("text-font", (const(listOf("Noto Sans Regular")).c()).asLayerProperty())
        },
        Case("text-size", "14.0") { it.layout("text-size", (const(14.dp).c()).asLayerProperty()) },
        Case("text-max-width", "9.0") {
          it.layout("text-max-width", (const(9f).c()).asLayerProperty())
        },
        Case("text-line-height", "1.25") {
          it.layout("text-line-height", (const(1.25f).c()).asLayerProperty())
        },
        Case("text-letter-spacing", "0.5") {
          it.layout("text-letter-spacing", (const(0.5f).c()).asLayerProperty())
        },
        Case("text-justify", "\"right\"") {
          it.layout("text-justify", (const(TextJustify.Right).c()).asLayerProperty())
        },
        Case("text-radial-offset", "1.5") {
          it.layout("text-radial-offset", (const(1.5f).c()).asLayerProperty())
        },
        Case("text-variable-anchor", """["top","bottom"]""", """["literal",["top","bottom"]]""") {
          it.layout(
            "text-variable-anchor",
            (const(listOf(SymbolAnchor.Top, SymbolAnchor.Bottom))
                .cast<ListValue<SymbolAnchor>>()
                .c())
              .asLayerProperty(),
          )
        },
        Case(
          "text-variable-anchor-offset",
          """["top",[0.0,1.0],"bottom",[0.0,-2.0]]""",
          """["let","semiliteral_value",["semiliteral",["top",["literal",[0,1]],"bottom",["literal",[0,-2]]]],["var","semiliteral_value"]]""",
        ) {
          it.layout(
            "text-variable-anchor-offset",
            (textVariableAnchorOffset(
                  SymbolAnchor.Top to textOffset(0.sp, 16.sp),
                  SymbolAnchor.Bottom to textOffset(0.em, (-2).em),
                )
                .compile(
                  object : ExpressionContext by ExpressionContext.None {
                    override val spScale = const(0.0625f)
                    override val emScale = const(1f)
                  }
                ))
              .asLayerProperty(),
          )
        },
        Case("text-anchor", "\"top-left\"") {
          it.layout("text-anchor", (const(SymbolAnchor.TopLeft).c()).asLayerProperty())
        },
        Case("text-max-angle", "30.0") {
          it.layout("text-max-angle", (const(30f).c()).asLayerProperty())
        },
        Case("text-writing-mode", """["horizontal"]""", """["literal",["horizontal"]]""") {
          it.layout(
            "text-writing-mode",
            (const(listOf(TextWritingMode.Horizontal)).cast<ListValue<TextWritingMode>>().c())
              .asLayerProperty(),
          )
        },
        Case("text-rotate", "90.0") {
          it.layout("text-rotate", (const(90f).c()).asLayerProperty())
        },
        Case("text-padding", "4.0") {
          it.layout("text-padding", (const(4.dp).c()).asLayerProperty())
        },
        Case("text-keep-upright", "false") {
          it.layout("text-keep-upright", (const(false).c()).asLayerProperty())
        },
        Case("text-transform", "\"uppercase\"") {
          it.layout("text-transform", (const(TextTransform.Uppercase).c()).asLayerProperty())
        },
        Case("text-offset", "[1.0,2.0]", """["literal",[1.0,2.0]]""") {
          it.layout("text-offset", (const(Offset(1f, 2f)).c()).asLayerProperty())
        },
        Case("text-allow-overlap", "true") {
          it.layout("text-allow-overlap", (const(true).c()).asLayerProperty())
        },
        Case("text-ignore-placement", "true") {
          it.layout("text-ignore-placement", (const(true).c()).asLayerProperty())
        },
        Case("text-optional", "true") {
          it.layout("text-optional", (const(true).c()).asLayerProperty())
        },
        Case("text-opacity", """["interpolate",["linear"],["zoom"],0.0,0.0,10.0,1.0]""") {
          it.paint(
            "text-opacity",
            (interpolate(linear(), zoom(), 0f to const(0f), 10f to const(1f))
                .cast<FloatValue>()
                .c())
              .asLayerProperty(),
          )
        },
        // MapLibre stores colours premultiplied as floats, so a fractional alpha comes back a
        // rounding step off the byte that was sent.
        Case(
          "text-color",
          """["rgba",17.0,34.0,51.0,0.5]""",
          "\"rgba(17, 34, 51, 0.5019607843137255)\"",
        ) {
          it.paint("text-color", (const(Color(0x80112233)).c()).asLayerProperty())
        },
        Case(
          "text-halo-color",
          """["rgba",255.0,255.0,255.0,1.0]""",
          "\"rgba(255, 255, 255, 1)\"",
        ) {
          it.paint("text-halo-color", (const(Color.White).c()).asLayerProperty())
        },
        Case("text-halo-width", "2.0") {
          it.paint("text-halo-width", (const(2.dp).c()).asLayerProperty())
        },
        Case("text-halo-blur", "1.0") {
          it.paint("text-halo-blur", (const(1.dp).c()).asLayerProperty())
        },
        Case("text-translate", "[5.0,6.0]", """["literal",[5.0,6.0]]""") {
          it.paint("text-translate", (const(DpOffset(5.dp, 6.dp)).c()).asLayerProperty())
        },
        Case("text-translate-anchor", "\"viewport\"") {
          it.paint("text-translate-anchor", (const(TranslateAnchor.Viewport).c()).asLayerProperty())
        },
        Case("text-opacity-transition", scaledTransitionJson(700.0, 50.0)) {
          it.paint("text-opacity", (const(1f).c()).asLayerProperty())
          it.paintTransition("text-opacity", TransitionOptions(700.milliseconds, 50.milliseconds))
        },
      ) + glJsOnlySymbolCases()

    /** Properties MapLibre GL JS implements and MapLibre Native does not, yet. */
    fun glJsOnlySymbolCases(): List<Case> =
      if (mapLibreFlavor != MapLibreFlavor.GL_JS) emptyList()
      else
        listOf(
          Case("icon-overlap", "\"cooperative\"") {
            it.layout("icon-overlap", (const("cooperative").c()).asLayerProperty())
          },
          Case("text-overlap", "\"cooperative\"") {
            it.layout("text-overlap", (const(SymbolOverlap.Cooperative).c()).asLayerProperty())
          },
          Case("symbol-height-offset", "15.0") {
            it.layout("symbol-height-offset", (const(15f).c()).asLayerProperty())
          },
          Case("symbol-height-anchor", "\"absolute\"") {
            it.layout(
              "symbol-height-anchor",
              (const(SymbolHeightAnchor.Absolute).c()).asLayerProperty(),
            )
          },
        )
  }
}

/**
 * The scale a reconciler passes to an installation; the direct installations here pass the same.
 */
private val scale: Float
  get() = systemAnimatorDurationScale()

/**
 * The engine JSON for a written transition: the timing under the platform's animator duration
 * scale.
 */
private fun scaledTransitionJson(durationMs: Double, delayMs: Double): String {
  val scale = scale.toDouble()
  return """{"duration":${durationMs * scale},"delay":${delayMs * scale}}"""
}
