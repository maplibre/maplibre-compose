package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReconstructedSourceTest {

  @Test
  fun a_known_type_reconstructs_as_the_matching_source_class() {
    val vector = reconstructedSource("vector", sourceJson("vector"))
    val raster = reconstructedSource("raster", sourceJson("raster"))
    val dem = reconstructedSource("dem", sourceJson("raster-dem"))
    val geojson = reconstructedSource("geojson", sourceJson("geojson"))
    val image = reconstructedSource("image", sourceJson("image"))
    val video = reconstructedSource("clip", sourceJson("video"))

    assertIs<VectorSource>(vector)
    assertIs<RasterSource>(raster)
    assertIs<RasterDemSource>(dem)
    assertIs<GeoJsonSource>(geojson)
    assertIs<ImageSource>(image)
    assertIs<VideoSource>(video)
    assertIs<RasterLayerSource>(video)
    assertEquals(JsonPrimitive("vector"), vector.toJson()["type"])
    assertEquals(JsonPrimitive("raster"), raster.toJson()["type"])
    assertEquals(JsonPrimitive("raster-dem"), dem.toJson()["type"])
    assertEquals(JsonPrimitive("geojson"), geojson.toJson()["type"])
    assertEquals(JsonPrimitive("image"), image.toJson()["type"])
    assertEquals(JsonPrimitive("video"), video.toJson()["type"])
  }

  @Test
  fun an_unrecognized_type_stays_unknown() {
    assertIs<UnknownSource>(reconstructedSource("future", sourceJson("not-a-spec-type")))
    assertIs<UnknownSource>(reconstructedSource("blank", buildJsonObject {}))
  }

  @Test
  fun reconstructed_json_round_trips_without_inventing_constructor_defaults() {
    val definition = buildJsonObject {
      put("type", "vector")
      put("url", "https://example.invalid/tiles.json")
      put("attribution", "© nobody")
    }
    val source = assertIs<VectorSource>(reconstructedSource("tiles", definition))
    assertEquals(definition, source.toJson())
    assertEquals("© nobody", source.attributionHtml)
  }

  private fun sourceJson(type: String) = buildJsonObject { put("type", type) }
}
