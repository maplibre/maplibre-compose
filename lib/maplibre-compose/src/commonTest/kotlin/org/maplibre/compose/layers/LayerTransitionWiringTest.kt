package org.maplibre.compose.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.RasterDemEncoding
import org.maplibre.compose.sources.RasterDemSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.animatorDurationScale
import org.maplibre.compose.testing.composeStyle
import org.maplibre.compose.testing.supportsComposeRuntimeTests
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

/**
 * Which composable parameter feeds which paint key. Every transition parameter and every transition
 * setter has the same type, so a swapped pair compiles and satisfies every other check; the
 * durations below are the contract, one distinct value per parameter.
 */
class LayerTransitionWiringTest {

  @Test
  fun every_transition_parameter_reaches_its_own_paint_key() = runTest {
    if (!supportsComposeRuntimeTests) return@runTest
    val features = featureSource()
    val raster = rasterSource()
    val dem = demSource()
    val style = composeStyle {
      SymbolLayer(
        id = "labels",
        source = features,
        iconOpacityTransition = timing(1),
        iconColorTransition = timing(2),
        iconHaloColorTransition = timing(3),
        iconHaloWidthTransition = timing(4),
        iconHaloBlurTransition = timing(5),
        iconTranslateTransition = timing(6),
        textOpacityTransition = timing(7),
        textColorTransition = timing(8),
        textHaloColorTransition = timing(9),
        textHaloWidthTransition = timing(10),
        textHaloBlurTransition = timing(11),
        textTranslateTransition = timing(12),
      )
      LineLayer(
        id = "lines",
        source = features,
        opacityTransition = timing(13),
        layerOpacityTransition = timing(14),
        colorTransition = timing(15),
        translateTransition = timing(16),
        widthTransition = timing(17),
        gapWidthTransition = timing(18),
        offsetTransition = timing(19),
        blurTransition = timing(20),
        dasharrayTransition = timing(21),
        patternTransition = timing(22),
      )
      CircleLayer(
        id = "circles",
        source = features,
        radiusTransition = timing(23),
        colorTransition = timing(24),
        blurTransition = timing(25),
        opacityTransition = timing(26),
        translateTransition = timing(27),
        strokeWidthTransition = timing(28),
        strokeColorTransition = timing(29),
        strokeOpacityTransition = timing(30),
      )
      FillLayer(
        id = "fills",
        source = features,
        opacityTransition = timing(31),
        layerOpacityTransition = timing(32),
        colorTransition = timing(33),
        outlineColorTransition = timing(34),
        translateTransition = timing(35),
        patternTransition = timing(36),
      )
      FillExtrusionLayer(
        id = "extrusions",
        source = features,
        opacityTransition = timing(37),
        colorTransition = timing(38),
        translateTransition = timing(39),
        patternTransition = timing(40),
        heightTransition = timing(41),
        baseTransition = timing(42),
      )
      RasterLayer(
        id = "rasters",
        source = raster,
        opacityTransition = timing(43),
        hueRotateTransition = timing(44),
        brightnessMinTransition = timing(45),
        brightnessMaxTransition = timing(46),
        saturationTransition = timing(47),
        contrastTransition = timing(48),
      )
      BackgroundLayer(
        id = "backgrounds",
        colorTransition = timing(49),
        patternTransition = timing(50),
        opacityTransition = timing(51),
      )
      ColorReliefLayer(id = "reliefs", source = dem, opacityTransition = timing(52))
      HeatmapLayer(
        id = "heatmaps",
        source = features,
        radiusTransition = timing(53),
        intensityTransition = timing(54),
        opacityTransition = timing(55),
      )
      HillshadeLayer(
        id = "hillshades",
        source = dem,
        exaggerationTransition = timing(56),
        shadowColorTransition = timing(57),
        highlightColorTransition = timing(58),
        accentColorTransition = timing(59),
      )
      // `visible` selects the composable: with only `id` and `source`, the call resolves to the
      // internal layer class of the same name instead.
      CircleLayer(id = "circles-untimed", source = features, visible = true)
    }

    val scale = animatorDurationScale().toDouble()
    val expected =
      mapOf(
        "labels" to
          mapOf(
            "icon-opacity-transition" to 1.0,
            "icon-color-transition" to 2.0,
            "icon-halo-color-transition" to 3.0,
            "icon-halo-width-transition" to 4.0,
            "icon-halo-blur-transition" to 5.0,
            "icon-translate-transition" to 6.0,
            "text-opacity-transition" to 7.0,
            "text-color-transition" to 8.0,
            "text-halo-color-transition" to 9.0,
            "text-halo-width-transition" to 10.0,
            "text-halo-blur-transition" to 11.0,
            "text-translate-transition" to 12.0,
          ),
        "lines" to
          mapOf(
            "line-opacity-transition" to 13.0,
            "line-layer-opacity-transition" to 14.0,
            "line-color-transition" to 15.0,
            "line-translate-transition" to 16.0,
            "line-width-transition" to 17.0,
            "line-gap-width-transition" to 18.0,
            "line-offset-transition" to 19.0,
            "line-blur-transition" to 20.0,
            "line-dasharray-transition" to 21.0,
            "line-pattern-transition" to 22.0,
          ),
        "circles" to
          mapOf(
            "circle-radius-transition" to 23.0,
            "circle-color-transition" to 24.0,
            "circle-blur-transition" to 25.0,
            "circle-opacity-transition" to 26.0,
            "circle-translate-transition" to 27.0,
            "circle-stroke-width-transition" to 28.0,
            "circle-stroke-color-transition" to 29.0,
            "circle-stroke-opacity-transition" to 30.0,
          ),
        "fills" to
          mapOf(
            "fill-opacity-transition" to 31.0,
            "fill-layer-opacity-transition" to 32.0,
            "fill-color-transition" to 33.0,
            "fill-outline-color-transition" to 34.0,
            "fill-translate-transition" to 35.0,
            "fill-pattern-transition" to 36.0,
          ),
        "extrusions" to
          mapOf(
            "fill-extrusion-opacity-transition" to 37.0,
            "fill-extrusion-color-transition" to 38.0,
            "fill-extrusion-translate-transition" to 39.0,
            "fill-extrusion-pattern-transition" to 40.0,
            "fill-extrusion-height-transition" to 41.0,
            "fill-extrusion-base-transition" to 42.0,
          ),
        "rasters" to
          mapOf(
            "raster-opacity-transition" to 43.0,
            "raster-hue-rotate-transition" to 44.0,
            "raster-brightness-min-transition" to 45.0,
            "raster-brightness-max-transition" to 46.0,
            "raster-saturation-transition" to 47.0,
            "raster-contrast-transition" to 48.0,
          ),
        "backgrounds" to
          mapOf(
            "background-color-transition" to 49.0,
            "background-pattern-transition" to 50.0,
            "background-opacity-transition" to 51.0,
          ),
        "reliefs" to mapOf("color-relief-opacity-transition" to 52.0),
        "heatmaps" to
          mapOf(
            "heatmap-radius-transition" to 53.0,
            "heatmap-intensity-transition" to 54.0,
            "heatmap-opacity-transition" to 55.0,
          ),
        "hillshades" to
          mapOf(
            "hillshade-exaggeration-transition" to 56.0,
            "hillshade-shadow-color-transition" to 57.0,
            "hillshade-highlight-color-transition" to 58.0,
            "hillshade-accent-color-transition" to 59.0,
          ),
        "circles-untimed" to emptyMap(),
      )

    // The recorded durations are the written timings under the platform's animator duration scale.
    // A zero scale collapses every value to zero; the key wiring is still what is asserted.
    assertEquals(
      expected.mapValues { (_, durations) ->
        durations.mapValues { (_, duration) -> duration * scale }
      },
      LAYER_IDS.associateWith { style.transitionDurations(it) },
    )
  }

  @Test
  fun a_fill_outline_color_transition_defaults_to_the_fill_color_transition() = runTest {
    if (!supportsComposeRuntimeTests) return@runTest
    val features = featureSource()
    val style = composeStyle {
      FillLayer(id = "fills", source = features, colorTransition = timing(400))
    }

    val scale = animatorDurationScale().toDouble()
    assertEquals(
      mapOf(
        "fill-color-transition" to 400.0 * scale,
        "fill-outline-color-transition" to 400.0 * scale,
      ),
      style.transitionDurations("fills"),
    )
  }

  /** The durations of every `-transition` key the layer [id] holds. */
  private fun RecordingStyleBinding.transitionDurations(id: String): Map<String, Double> {
    val paint =
      assertNotNull(getLayer(id), "the composition should have installed '$id'").toJson()["paint"]
        as? JsonObject
    return paint
      .orEmpty()
      .filterKeys { it.endsWith("-transition") }
      .mapValues { (_, value) -> (value as JsonObject).getValue("duration").jsonPrimitive.double }
  }

  private companion object {
    /** Unresolvable on purpose: tests must not reach the network. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"

    val LAYER_IDS =
      listOf(
        "labels",
        "lines",
        "circles",
        "fills",
        "extrusions",
        "rasters",
        "backgrounds",
        "reliefs",
        "heatmaps",
        "hillshades",
        "circles-untimed",
      )

    fun featureSource() =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

    fun rasterSource() =
      RasterSource(
        id = "raster",
        tiles = listOf(TILE_TEMPLATE),
        options = TileSetOptions(),
        tileSize = 256,
      )

    fun demSource() =
      RasterDemSource(
        id = "dem",
        tiles = listOf(TILE_TEMPLATE),
        options = TileSetOptions(),
        tileSize = 256,
        demEncoding = RasterDemEncoding.Terrarium,
      )

    fun timing(milliseconds: Int) = TransitionOptions(milliseconds.milliseconds)
  }
}
