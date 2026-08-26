package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.style.RecordingStyleBinding

/** What a raster-dem source sends depends on what the engine it attaches to implements. */
class RasterDemSourceJsonTest {

  @Test
  fun the_descriptor_keeps_the_scheme_and_encoding_it_was_given() {
    val json =
      RasterDemSource(
          id = "dem",
          tiles = listOf(TILE_TEMPLATE),
          options = TileSetOptions(tileCoordinateSystem = TileCoordinateSystem.TMS),
          demEncoding = RasterDemEncoding.Custom(redFactor = 2f),
        )
        .toJson()

    assertEquals("tms", json["scheme"]?.jsonPrimitive?.content)
    assertEquals("custom", json["encoding"]?.jsonPrimitive?.content)
    assertEquals(2f, json["redFactor"]?.jsonPrimitive?.content?.toFloat())
  }

  @Test
  fun an_engine_without_the_custom_encoding_takes_mapbox() {
    val binding = RecordingStyleBinding(supportsCustomDemEncoding = false)
    val source =
      RasterDemSource(
        id = "dem",
        tiles = listOf(TILE_TEMPLATE),
        demEncoding = RasterDemEncoding.Custom(redFactor = 2f),
      )

    source.attach(binding)

    val json = assertNotNull(binding.sources["dem"])
    assertEquals("mapbox", json["encoding"]?.jsonPrimitive?.content)
    assertFalse("redFactor" in json)
  }

  @Test
  fun an_engine_with_the_custom_encoding_takes_its_factors() {
    val binding = RecordingStyleBinding(supportsCustomDemEncoding = true)
    val source =
      RasterDemSource(
        id = "dem",
        tiles = listOf(TILE_TEMPLATE),
        demEncoding = RasterDemEncoding.Custom(redFactor = 2f, baseShift = 3f),
      )

    source.attach(binding)

    val json = assertNotNull(binding.sources["dem"])
    assertEquals("custom", json["encoding"]?.jsonPrimitive?.content)
    assertEquals(2f, json["redFactor"]?.jsonPrimitive?.content?.toFloat())
    assertEquals(3f, json["baseShift"]?.jsonPrimitive?.content?.toFloat())
  }

  @Test
  fun an_engine_without_the_scheme_key_never_sees_it() {
    val binding = RecordingStyleBinding(supportsRasterDemScheme = false)
    val source =
      RasterDemSource(
        id = "dem",
        tiles = listOf(TILE_TEMPLATE),
        options = TileSetOptions(tileCoordinateSystem = TileCoordinateSystem.XYZ),
      )

    source.attach(binding)

    assertFalse("scheme" in assertNotNull(binding.sources["dem"]))
  }

  @Test
  fun tms_tiles_fail_on_an_engine_without_the_scheme_key() {
    val binding = RecordingStyleBinding(supportsRasterDemScheme = false)
    val source =
      RasterDemSource(
        id = "dem",
        tiles = listOf(TILE_TEMPLATE),
        options = TileSetOptions(tileCoordinateSystem = TileCoordinateSystem.TMS),
      )

    val error = assertFailsWith<IllegalStateException> { source.attach(binding) }

    assertContains(error.message.orEmpty(), "TileCoordinateSystem.XYZ")
    assertFalse("dem" in binding.sources)
  }

  @Test
  fun a_tile_json_source_carries_neither_key() {
    val binding = RecordingStyleBinding(supportsRasterDemScheme = false)
    val source = RasterDemSource(id = "dem", uri = "https://example.invalid/tiles.json")

    source.attach(binding)

    val json = assertNotNull(binding.sources["dem"])
    assertFalse("scheme" in json)
    assertFalse("encoding" in json)
  }

  private companion object {
    /** Unresolvable on purpose: tests must not reach the network. */
    const val TILE_TEMPLATE = "https://example.invalid/{z}/{x}/{y}.png"
  }
}
