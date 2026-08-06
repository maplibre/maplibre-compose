package org.maplibre.compose.layers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
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
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.IconTextFit
import org.maplibre.compose.expressions.value.IlluminationAnchor
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.RasterResampling
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.expressions.value.SymbolZOrder
import org.maplibre.compose.expressions.value.TextJustify
import org.maplibre.compose.expressions.value.TextPitchAlignment
import org.maplibre.compose.expressions.value.TextRotationAlignment
import org.maplibre.compose.expressions.value.TextTransform
import org.maplibre.compose.expressions.value.TextWritingMode
import org.maplibre.compose.expressions.value.TranslateAnchor
import org.maplibre.compose.mlnffi.HeadlessMapFixture
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.RasterDemEncoding
import org.maplibre.compose.sources.RasterDemSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.compose.util.onMap
import org.maplibre.compose.util.toJsonElement
import org.maplibre.nativeffi.style.StyleLayerVisibility
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Sets each desktop layer setter and reads the value back off the live map.
 *
 * MapLibre silently clamps, ignores, or defaults a value of the wrong shape, so the assertion has
 * to be what it reports back through `layerProperty`. Every property is exercised on both paths a
 * descriptor has to the map: accumulated into the JSON that creates the layer, and set per-property
 * on the live layer afterwards.
 *
 * Expected values are MapLibre's own re-serialization, not what was written.
 */
class LayerPropertyRoundTripTest {

  @Test
  fun `background layer properties reach MapLibre`() {
    assertPropertiesRoundTrip(BACKGROUND_CASES) { _ -> ({ id -> BackgroundLayer(id) }) }
  }

  @Test
  fun `circle layer properties reach MapLibre`() {
    assertPropertiesRoundTrip(CIRCLE_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> CircleLayer(id, source) })
    }
  }

  @Test
  fun `fill layer properties reach MapLibre`() {
    assertPropertiesRoundTrip(FILL_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> FillLayer(id, source) })
    }
  }

  @Test
  fun `fill extrusion layer properties reach MapLibre`() {
    assertPropertiesRoundTrip(FILL_EXTRUSION_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> FillExtrusionLayer(id, source) })
    }
  }

  @Test
  fun `heatmap layer properties reach MapLibre`() {
    assertPropertiesRoundTrip(HEATMAP_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> HeatmapLayer(id, source) })
    }
  }

  @Test
  fun `line layer properties reach MapLibre`() {
    assertPropertiesRoundTrip(LINE_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> LineLayer(id, source) })
    }
  }

  @Test
  fun `symbol layer properties reach MapLibre`() {
    assertPropertiesRoundTrip(SYMBOL_CASES) { style ->
      val source = addFeatureSource(style)
      ({ id -> SymbolLayer(id, source) })
    }
  }

  @Test
  fun `raster layer properties reach MapLibre`() {
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
  fun `hillshade layer properties reach MapLibre`() {
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

  /**
   * The keys handled by the layer base class rather than by the generated properties, so
   * `layerProperty` does not answer for them and they are read through the calls that do.
   */
  @Test
  fun `the common layer keys reach MapLibre before and after attach`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")
      val source = addFeatureSource(style)

      val beforeAttach = SymbolLayer("before", source)
      beforeAttach.sourceLayer = "places"
      beforeAttach.minZoom = 3f
      beforeAttach.maxZoom = 15f
      beforeAttach.visible = false
      beforeAttach.setFilter((Feature["class"].cast<StringValue>() eq const("park")).c())
      style.addLayer(beforeAttach)

      val afterAttach = SymbolLayer("after", source)
      style.addLayer(afterAttach)
      afterAttach.sourceLayer = "roads"
      afterAttach.minZoom = 4f
      afterAttach.maxZoom = 16f
      afterAttach.visible = false
      afterAttach.setFilter((Feature["class"].cast<StringValue>() eq const("wood")).c())

      beforeAttach.onMap { map ->
        assertEquals("places", map.layerSourceLayer("before"))
        assertEquals(SOURCE_ID, map.layerSourceId("before"))
        assertEquals(3.0, map.layerMinZoom("before"))
        assertEquals(15.0, map.layerMaxZoom("before"))
        assertEquals(StyleLayerVisibility.NONE, map.layerVisibility("before"))
        assertEquals(
          Json.parseToJsonElement("""["==",["get","class"],"park"]"""),
          map.layerFilter("before")?.toJsonElement(),
        )

        assertEquals("roads", map.layerSourceLayer("after"))
        assertEquals(4.0, map.layerMinZoom("after"))
        assertEquals(16.0, map.layerMaxZoom("after"))
        assertEquals(StyleLayerVisibility.NONE, map.layerVisibility("after"))
        assertEquals(
          Json.parseToJsonElement("""["==",["get","class"],"wood"]"""),
          map.layerFilter("after")?.toJsonElement(),
        )
      }
      assertEquals(emptyList(), it.errors, "the map should report nothing")
    }
  }

  /**
   * Runs every case twice against one map: once written before the layer is added, once after.
   * Failures are collected rather than thrown at the first mismatch.
   *
   * @param prepare adds whatever sources the layer type needs to [MlnFfiStyle] and returns a
   *   factory for the layer under test. Each case gets its own layer so one rejected property
   *   cannot mask another.
   */
  private fun <L : Layer> assertPropertiesRoundTrip(
    cases: List<Case<L>>,
    prepare: (MlnFfiStyle) -> (String) -> L,
  ) {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(it.style as? MlnFfiStyle, "Errors: ${it.errors}")
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
    style: MlnFfiStyle,
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
    val actual =
      layer.binding.withMap { map -> map.layerProperty(layer.id, case.property)?.toJsonElement() }
    val expected = Json.parseToJsonElement(case.expected)
    return if (actual == expected) emptyList()
    else listOf("${case.property} $path: expected $expected but MapLibre reports $actual")
  }

  /** A property to write, and the value MapLibre should report for it afterwards. */
  private class Case<in L : Layer>(
    val property: String,
    /** The expected value as style JSON, parsed rather than compared as text. */
    val expected: String,
    val apply: (L) -> Unit,
  )

  private companion object {
    const val SOURCE_ID = "features"

    /** Unresolvable on purpose: a test that reaches the network is worse than no test. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"

    fun <T : ExpressionValue> Expression<T>.c() = compile(ExpressionContext.None)

    fun addFeatureSource(style: MlnFfiStyle): Source =
      GeoJsonSource(
          id = SOURCE_ID,
          data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>()),
          options = GeoJsonOptions(),
        )
        .also { style.addSource(it) }

    val BACKGROUND_CASES =
      listOf<Case<BackgroundLayer>>(
        Case("background-color", """["rgba",0.0,0.0,255.0,1.0]""") {
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
        Case("circle-color", """["rgba",255.0,0.0,0.0,1.0]""") {
          it.setCircleColor(const(Color.Red).c())
        },
        Case("circle-blur", "0.25") { it.setCircleBlur(const(0.25f).c()) },
        Case("circle-opacity", "0.5") { it.setCircleOpacity(const(0.5f).c()) },
        Case("circle-translate", "[1.0,2.0]") {
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
        Case("circle-stroke-color", """["rgba",0.0,0.0,0.0,1.0]""") {
          it.setCircleStrokeColor(const(Color.Black).c())
        },
        Case("circle-stroke-opacity", "0.75") { it.setCircleStrokeOpacity(const(0.75f).c()) },
      )

    val FILL_CASES =
      listOf<Case<FillLayer>>(
        Case("fill-sort-key", "2.0") { it.setFillSortKey(const(2f).c()) },
        Case("fill-antialias", "false") { it.setFillAntialias(const(false).c()) },
        Case("fill-opacity", "0.5") { it.setFillOpacity(const(0.5f).c()) },
        Case("fill-color", """["rgba",0.0,255.0,0.0,1.0]""") {
          it.setFillColor(const(Color.Green).c())
        },
        Case("fill-outline-color", """["rgba",0.0,0.0,0.0,1.0]""") {
          it.setFillOutlineColor(const(Color.Black).c())
        },
        Case("fill-translate", "[1.0,2.0]") {
          it.setFillTranslate(const(DpOffset(1.dp, 2.dp)).c())
        },
        Case("fill-translate-anchor", "\"viewport\"") {
          it.setFillTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
        Case("fill-pattern", """["image","brick"]""") { it.setFillPattern(image("brick").c()) },
      )

    val FILL_EXTRUSION_CASES =
      listOf<Case<FillExtrusionLayer>>(
        Case("fill-extrusion-opacity", "0.5") { it.setFillExtrusionOpacity(const(0.5f).c()) },
        Case("fill-extrusion-color", """["rgba",255.0,0.0,255.0,1.0]""") {
          it.setFillExtrusionColor(const(Color.Magenta).c())
        },
        Case("fill-extrusion-translate", "[7.0,8.0]") {
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
        Case("line-color", """["rgba",0.0,0.0,255.0,1.0]""") {
          it.setLineColor(const(Color.Blue).c())
        },
        Case("line-translate", "[1.0,2.0]") {
          it.setLineTranslate(const(DpOffset(1.dp, 2.dp)).c())
        },
        Case("line-translate-anchor", "\"viewport\"") {
          it.setLineTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
        Case("line-width", "3.0") { it.setLineWidth(const(3.dp).c()) },
        Case("line-gap-width", "1.0") { it.setLineGapWidth(const(1.dp).c()) },
        Case("line-offset", "2.0") { it.setLineOffset(const(2.dp).c()) },
        Case("line-blur", "1.0") { it.setLineBlur(const(1.dp).c()) },
        Case("line-dasharray", "[2.0,4.0]") { it.setLineDasharray(const(listOf(2, 4)).c()) },
        Case("line-pattern", """["image","dash"]""") { it.setLinePattern(image("dash").c()) },
        // Like heatmap-color, a ramp rather than a constant, and only over line-progress.
        Case(
          "line-gradient",
          """["interpolate",["linear"],["line-progress"],
             0.0,["rgba",0.0,0.0,255.0,1.0],1.0,["rgba",255.0,0.0,0.0,1.0]]""",
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
      )

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
        // Milliseconds; the only property whose Kotlin type is a Duration.
        Case("raster-fade-duration", "250.0") {
          it.setRasterFadeDuration(const(250.milliseconds).c())
        },
      )

    val HILLSHADE_CASES =
      listOf<Case<HillshadeLayer>>(
        // Reported inside an array: MapLibre Native's hillshade takes a list of light sources.
        Case("hillshade-illumination-direction", "[200.0]") {
          it.setHillshadeIlluminationDirection(const(200f).c())
        },
        Case("hillshade-illumination-anchor", "\"map\"") {
          it.setHillshadeIlluminationAnchor(const(IlluminationAnchor.Map).c())
        },
        Case("hillshade-exaggeration", "0.5") { it.setHillshadeExaggeration(const(0.5f).c()) },
        Case("hillshade-shadow-color", """[["rgba",0.0,0.0,0.0,1.0]]""") {
          it.setHillshadeShadowColor(const(Color.Black).c())
        },
        Case("hillshade-highlight-color", """[["rgba",255.0,255.0,255.0,1.0]]""") {
          it.setHillshadeHighlightColor(const(Color.White).c())
        },
        // Not a list: the accent colour is one colour however many lights there are.
        Case("hillshade-accent-color", """["rgba",0.0,255.0,255.0,1.0]""") {
          it.setHillshadeAccentColor(const(Color.Cyan).c())
        },
      )

    val SYMBOL_CASES =
      listOf<Case<SymbolLayer>>(
        Case("symbol-placement", "\"line\"") {
          it.setSymbolPlacement(const(SymbolPlacement.Line).c())
        },
        Case("symbol-spacing", "30.0") { it.setSymbolSpacing(const(30.dp).c()) },
        Case("symbol-avoid-edges", "true") { it.setSymbolAvoidEdges(const(true).c()) },
        // Data-driven: MapLibre wraps it in the coercion the property's type implies.
        Case("symbol-sort-key", """["number",["get","rank"]]""") {
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
        // Style order is top, right, bottom, left, which is not the order PaddingValues reads in.
        Case("icon-text-fit-padding", "[2.0,3.0,4.0,1.0]") {
          it.setIconTextFitPadding(const(PaddingValues.Absolute(1.dp, 2.dp, 3.dp, 4.dp)).c())
        },
        Case("icon-image", """["image","marker"]""") { it.setIconImage(image("marker").c()) },
        Case("icon-rotate", "45.0") { it.setIconRotate(const(45f).c()) },
        Case("icon-padding", "[2.0,3.0,4.0,1.0]") {
          it.setIconPadding(const(PaddingValues.Absolute(1.dp, 2.dp, 3.dp, 4.dp)).c())
        },
        Case("icon-keep-upright", "true") { it.setIconKeepUpright(const(true).c()) },
        Case("icon-offset", "[3.0,4.0]") { it.setIconOffset(const(DpOffset(3.dp, 4.dp)).c()) },
        Case("icon-anchor", "\"bottom-left\"") {
          it.setIconAnchor(const(SymbolAnchor.BottomLeft).c())
        },
        Case("icon-pitch-alignment", "\"viewport\"") {
          it.setIconPitchAlignment(const(IconPitchAlignment.Viewport).c())
        },
        Case("icon-opacity", "0.25") { it.setIconOpacity(const(0.25f).c()) },
        Case("icon-color", """["rgba",255.0,0.0,0.0,1.0]""") {
          it.setIconColor(const(Color.Red).c())
        },
        Case("icon-halo-color", """["rgba",0.0,0.0,255.0,1.0]""") {
          it.setIconHaloColor(const(Color.Blue).c())
        },
        Case("icon-halo-width", "2.0") { it.setIconHaloWidth(const(2.dp).c()) },
        Case("icon-halo-blur", "1.0") { it.setIconHaloBlur(const(1.dp).c()) },
        Case("icon-translate", "[5.0,6.0]") {
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
        ) {
          it.setTextField(format(span("Hello")).c())
        },
        Case("text-font", """["Noto Sans Regular"]""") {
          it.setTextFont(const(listOf("Noto Sans Regular")).c())
        },
        Case("text-size", "14.0") { it.setTextSize(const(14.dp).c()) },
        Case("text-max-width", "9.0") { it.setTextMaxWidth(const(9f).c()) },
        Case("text-line-height", "1.25") { it.setTextLineHeight(const(1.25f).c()) },
        Case("text-letter-spacing", "0.5") { it.setTextLetterSpacing(const(0.5f).c()) },
        Case("text-justify", "\"right\"") { it.setTextJustify(const(TextJustify.Right).c()) },
        Case("text-radial-offset", "1.5") { it.setTextRadialOffset(const(1.5f).c()) },
        Case("text-variable-anchor", """["top","bottom"]""") {
          it.setTextVariableAnchor(
            const(listOf(SymbolAnchor.Top, SymbolAnchor.Bottom)).cast<ListValue<SymbolAnchor>>().c()
          )
        },
        Case("text-variable-anchor-offset", """["top",[0.0,1.0]]""") {
          it.setTextVariableAnchorOffset(
            textVariableAnchorOffset(SymbolAnchor.Top to Offset(0f, 1f)).c()
          )
        },
        Case("text-anchor", "\"top-left\"") { it.setTextAnchor(const(SymbolAnchor.TopLeft).c()) },
        Case("text-max-angle", "30.0") { it.setTextMaxAngle(const(30f).c()) },
        Case("text-writing-mode", """["horizontal"]""") {
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
        Case("text-offset", "[1.0,2.0]") { it.setTextOffset(const(Offset(1f, 2f)).c()) },
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
        Case("text-color", """["rgba",17.0,34.0,51.000003814697266,0.5]""") {
          it.setTextColor(const(Color(0x80112233)).c())
        },
        Case("text-halo-color", """["rgba",255.0,255.0,255.0,1.0]""") {
          it.setTextHaloColor(const(Color.White).c())
        },
        Case("text-halo-width", "2.0") { it.setTextHaloWidth(const(2.dp).c()) },
        Case("text-halo-blur", "1.0") { it.setTextHaloBlur(const(1.dp).c()) },
        Case("text-translate", "[5.0,6.0]") {
          it.setTextTranslate(const(DpOffset(5.dp, 6.dp)).c())
        },
        Case("text-translate-anchor", "\"viewport\"") {
          it.setTextTranslateAnchor(const(TranslateAnchor.Viewport).c())
        },
      )
  }
}
