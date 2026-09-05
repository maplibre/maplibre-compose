package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class NativeMapSnapshotterTest {

  @Test
  fun factory_rejects_a_runtime_without_an_offscreen_backend() {
    val factory =
      NativeSnapshotterAdapterFactory(
        options = MlnFfiRuntimeOptions(cacheFile = Path("unused"), logger = null),
        resourceConfig = MapResourceConfig(),
        runtimeBackends = { emptySet() },
      )

    assertFailsWith<UnsupportedOperationException> { factory.create() }
  }

  @Test
  fun composed_source_and_layer_render_into_an_offscreen_snapshot(): MapTestResult = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
      )
    try {
      val snapshotter = runtime.createSnapshotter(BASE_STYLE, pointComposition())
      try {
        val densityOne =
          snapshotter.capture(
            MapSnapshotRequest(
              width = SIZE,
              height = SIZE,
              cameraPosition =
                CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 2.0),
            )
          )
        val densityTwo =
          snapshotter.capture(
            MapSnapshotRequest(
              width = SIZE,
              height = SIZE,
              density = 2f,
              cameraPosition =
                CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 2.0),
            )
          )

        assertEquals(SIZE, densityOne.width)
        assertEquals(SIZE, densityOne.height)
        assertEquals(SIZE * 2, densityTwo.width)
        assertEquals(SIZE * 2, densityTwo.height)
        assertEquals(BACKGROUND, densityOne.readPixel(SIZE - 6, SIZE / 2))
        assertEquals(BACKGROUND, densityTwo.readPixel(SIZE * 2 - 12, SIZE))
        assertEquals(GREEN, densityOne.readPixel(SIZE / 2, SIZE / 2))
        assertEquals(GREEN, densityTwo.readPixel(SIZE, SIZE))
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
      }
    } finally {
      runtime.close()
      runtime.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun returning_to_an_equal_base_style_creates_a_fresh_style_identity(): MapTestResult =
    runMapTest {
      FfiTestPlatform.initialize()
      val cacheFile = FfiTestPlatform.createCacheFile()
      val runtime =
        createNativeMapRuntime(
          MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
        )
      try {
        val snapshotter = runtime.createSnapshotter(BASE_STYLE, pointComposition())
        try {
          val request =
            MapSnapshotRequest(
              width = SIZE,
              height = SIZE,
              cameraPosition = CameraPosition(zoom = 2.0),
            )
          snapshotter.capture(request)

          snapshotter.style.baseStyle = ALTERNATE_STYLE
          snapshotter.style.baseStyle = BASE_STYLE
          val captured = snapshotter.capture(request)

          assertEquals(GREEN, captured.readPixel(SIZE / 2, SIZE / 2))
        } finally {
          snapshotter.close()
          snapshotter.awaitClosed()
        }
      } finally {
        runtime.close()
        runtime.awaitClosed()
        FfiTestPlatform.deleteCacheFile(cacheFile)
      }
    }

  @Test
  fun content_sees_the_viewport_of_the_capture_it_is_evaluated_for(): MapTestResult = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
      )
    val sizes = mutableListOf<DpSize?>()
    try {
      val snapshotter =
        runtime.createSnapshotter(BASE_STYLE) { sizes += LocalViewport.current?.size }
      try {
        snapshotter.capture(MapSnapshotRequest(width = 31, height = 23))

        assertEquals(setOf(DpSize(31.dp, 23.dp)), sizes.toSet())
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
      }
    } finally {
      runtime.close()
      runtime.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun a_rejected_inline_style_cannot_fail_the_next_capture(): MapTestResult = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
      )
    try {
      val snapshotter = runtime.createSnapshotter(BaseStyle.Json("{not json}"))
      try {
        val rejected = runCatching { snapshotter.capture(MapSnapshotRequest(SIZE, SIZE)) }
        assertTrue(rejected.isFailure)

        snapshotter.style.baseStyle = BASE_STYLE
        val captured = snapshotter.capture(MapSnapshotRequest(SIZE, SIZE))

        assertEquals(BACKGROUND, captured.readPixel(0, 0))
      } finally {
        snapshotter.close()
        snapshotter.awaitClosed()
      }
    } finally {
      runtime.close()
      runtime.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun invalid_initial_geojson_fails_capture_and_corrected_data_renders(): MapTestResult =
    runMapTest {
      FfiTestPlatform.initialize()
      val cacheFile = FfiTestPlatform.createCacheFile()
      val runtime =
        createNativeMapRuntime(
          MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
        )
      val data = mutableStateOf<GeoJsonData>(GeoJsonData.JsonString("{not json}"))
      try {
        val snapshotter = runtime.createSnapshotter(BASE_STYLE, pointComposition { data.value })
        try {
          val request =
            MapSnapshotRequest(
              width = SIZE,
              height = SIZE,
              cameraPosition = CameraPosition(zoom = 2.0),
            )
          assertFailsWith<MapSnapshotException> { snapshotter.capture(request) }
          assertFailsWith<MapSnapshotException> { snapshotter.capture(request) }

          data.value = POINT_DATA
          val captured = snapshotter.capture(request)

          assertEquals(GREEN, captured.readPixel(SIZE / 2, SIZE / 2))
        } finally {
          snapshotter.close()
          snapshotter.awaitClosed()
        }
      } finally {
        runtime.close()
        runtime.awaitClosed()
        FfiTestPlatform.deleteCacheFile(cacheFile)
      }
    }

  private fun pointComposition(
    data: () -> GeoJsonData = { POINT_DATA }
  ): @Composable @MaplibreComposable () -> Unit {
    return {
      val points = GeoJsonSource(id = "points", data = data(), options = GeoJsonOptions())
      CircleLayer(
        id = "composed-circle",
        source = points,
        color = const(Color.Green),
        radius = const(20.dp),
      )
    }
  }

  private fun androidx.compose.ui.graphics.ImageBitmap.readPixel(x: Int, y: Int): RgbaPixel {
    val pixel = IntArray(1)
    readPixels(
      buffer = pixel,
      startX = x,
      startY = y,
      width = 1,
      height = 1,
    )
    return pixel.single().toRgbaPixel()
  }

  private fun Int.toRgbaPixel() =
    RgbaPixel(
      red = this ushr 16 and 0xff,
      green = this ushr 8 and 0xff,
      blue = this and 0xff,
      alpha = this ushr 24 and 0xff,
    )

  private companion object {
    const val SIZE = 64
    val BACKGROUND = RgbaPixel(red = 51, green = 102, blue = 153, alpha = 255)
    val GREEN = RgbaPixel(red = 0, green = 255, blue = 0, alpha = 255)
    val POINT_DATA =
      GeoJsonData.Features(
        buildFeatureCollection<Geometry, JsonObject?> {
          addFeature(geometry = Point(Position(longitude = 0.0, latitude = 0.0)))
        }
      )
    val BASE_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"base-background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
    val ALTERNATE_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"alternate","type":"background"}]}"""
      )
  }
}
