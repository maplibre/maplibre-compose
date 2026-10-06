package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class NativeMapSnapshotterTest {

  @Test
  fun snapshotter_rejects_a_runtime_without_an_offscreen_backend() {
    assertFailsWith<UnsupportedOperationException> {
      createNativeSnapshotterAdapter(
        owner = MlnFfiRuntime(MlnFfiRuntimeOptions(cacheFile = Path("unused"), logger = null)),
        backends = emptySet(),
      )
    }
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
      val snapshotter = runtime.createSnapshotter(BackgroundStyle, pointComposition())
      try {
        val densityOne =
          snapshotter.capture(
            MapSnapshotRequest(
              size = DpSize(Size.dp, Size.dp),
              cameraPosition =
                CameraPosition(
                  target = Position(longitude = 0.0, latitude = 0.0),
                  zoom = 2.0,
                  padding = DpPadding(left = 24.dp, bottom = 16.dp),
                ),
            )
          )
        val densityTwo =
          snapshotter.capture(
            MapSnapshotRequest(
              size = DpSize(Size.dp, Size.dp),
              density = Density(2f),
              cameraPosition =
                CameraPosition(
                  target = Position(longitude = 0.0, latitude = 0.0),
                  zoom = 2.0,
                  padding = DpPadding(left = 24.dp, bottom = 16.dp),
                ),
            )
          )

        assertEquals(Size, densityOne.width)
        assertEquals(Size, densityOne.height)
        assertEquals(Size * 2, densityTwo.width)
        assertEquals(Size * 2, densityTwo.height)
        assertEquals(Background, densityOne.readPixel(6, Size / 2))
        assertEquals(Background, densityTwo.readPixel(12, Size))
        assertEquals(Green, densityOne.readPixel(Size - 6, Size / 2 - 8))
        assertEquals(Green, densityTwo.readPixel(Size * 2 - 12, Size - 16))
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

  /**
   * A cancelled capture leaves its still image to the engine thread, which renders it from update
   * events whether or not anyone waits. Each later capture must still render its own image.
   */
  @Test
  fun an_abandoned_capture_finishes_on_the_engine_and_the_next_capture_renders(): MapTestResult =
    runMapTest {
      FfiTestPlatform.initialize()
      val cacheFile = FfiTestPlatform.createCacheFile()
      val runtime =
        createNativeMapRuntime(
          MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
        )
      val request =
        MapSnapshotRequest(
          size = DpSize(Size.dp, Size.dp),
          cameraPosition =
            CameraPosition(target = Position(longitude = 0.0, latitude = 0.0), zoom = 2.0),
        )
      try {
        val snapshotter = runtime.createSnapshotter(BackgroundStyle, pointComposition())
        try {
          // A missing wake would park the engine forever; the bound turns that into a failure.
          withTimeout(60_000) {
            for (delayMillis in listOf(0L, 1L, 5L, 20L, 50L)) {
              val abandoned = launch { snapshotter.capture(request) }
              delay(delayMillis)
              abandoned.cancelAndJoin()

              val image = snapshotter.capture(request)
              assertEquals(Background, image.readPixel(6, Size / 2), "after $delayMillis ms")
              assertEquals(Green, image.readPixel(Size / 2, Size / 2), "after $delayMillis ms")
            }
          }
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
        val snapshotter = runtime.createSnapshotter(BackgroundStyle, pointComposition())
        try {
          val request =
            MapSnapshotRequest(
              size = DpSize(Size.dp, Size.dp),
              cameraPosition = CameraPosition(zoom = 2.0),
            )
          snapshotter.capture(request)

          snapshotter.style.asMutable!!.baseStyle = AlternateStyle
          snapshotter.style.asMutable!!.baseStyle = BackgroundStyle
          val captured = snapshotter.capture(request)

          assertEquals(Green, captured.readPixel(Size / 2, Size / 2))
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
        val rejected = runCatching {
          snapshotter.capture(MapSnapshotRequest(DpSize(Size.dp, Size.dp)))
        }
        assertTrue(rejected.isFailure)

        snapshotter.style.asMutable!!.baseStyle = BackgroundStyle
        val captured = snapshotter.capture(MapSnapshotRequest(DpSize(Size.dp, Size.dp)))

        assertEquals(Background, captured.readPixel(0, 0))
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
        val snapshotter =
          runtime.createSnapshotter(BackgroundStyle, pointComposition { data.value })
        try {
          val request =
            MapSnapshotRequest(
              size = DpSize(Size.dp, Size.dp),
              cameraPosition = CameraPosition(zoom = 2.0),
            )
          assertFailsWith<MapSnapshotException> { snapshotter.capture(request) }
          assertFailsWith<MapSnapshotException> { snapshotter.capture(request) }

          data.value = PointData
          val captured = snapshotter.capture(request)

          assertEquals(Green, captured.readPixel(Size / 2, Size / 2))
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
    data: () -> GeoJsonData = { PointData }
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
    const val Size = 64
    val Background = RgbaPixel(red = 51, green = 102, blue = 153, alpha = 255)
    val Green = RgbaPixel(red = 0, green = 255, blue = 0, alpha = 255)
    val PointData =
      GeoJsonData.Features(
        buildFeatureCollection<Geometry, JsonObject?> {
          addFeature(geometry = Point(Position(longitude = 0.0, latitude = 0.0)))
        }
      )
    val BackgroundStyle =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"base-background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
    val AlternateStyle =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"alternate","type":"background"}]}"""
      )
  }
}
