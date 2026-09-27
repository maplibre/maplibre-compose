package org.maplibre.compose.map

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SnapshotCompositionTest {
  @Test
  fun snapshot_disposes_style_effects_without_holding_resource_commands() = runTest {
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { FakeSnapshotterAdapter() })
    val cleanup = CompletableDeferred<Result<Unit>>()
    try {
      lateinit var snapshotter: MapSnapshotter
      snapshotter =
        runtime.createSnapshotter(BaseStyle.Empty) {
          val scope = rememberCoroutineScope()
          DisposableEffect(Unit) {
            val job =
              scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                  awaitCancellation()
                } finally {
                  withContext(NonCancellable) {
                    cleanup.complete(
                      runCatching { withTimeout(5_000) { snapshotter.style.awaitCommands() } }
                    )
                  }
                }
              }
            onDispose { job.cancel() }
          }
        }
      snapshotter.capture(MapSnapshotRequest(4, 4))
      cleanup.await().getOrThrow()
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun snapshot_waits_for_painter_pixels_and_the_compiled_image_property() = runTest {
    val adapter =
      FakeSnapshotterAdapter(
        capture = { _, revision ->
          val captured = revision.images.single()
          val layout = revision.layers.single().definition.value["layout"] as JsonObject
          assertEquals(
            JsonArray(listOf(JsonPrimitive("image"), JsonPrimitive(captured.id))),
            layout["icon-image"],
          )
          captured.image.toImageBitmap()
        }
      )
    val runtime = mapRuntimeForTest(createSnapshotterAdapter = { adapter })
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val painter = ColorPainter(Color.Red)
    try {
      val snapshotter =
        runtime.createSnapshotter(BaseStyle.Empty) {
          SymbolLayer(
            id = "pin",
            source = source,
            iconImage = image(painter, size = DpSize(4.dp, 4.dp)),
          )
        }
      val bitmap = snapshotter.capture(MapSnapshotRequest(4, 4))
      val pixels = IntArray(16)
      bitmap.readPixels(pixels)
      assertEquals(List(16) { 0xffff0000.toInt() }, pixels.toList())
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun each_capture_composes_current_state_in_its_own_environment() = runTest {
    data class Evaluation(
      val value: String,
      val viewport: DpSize?,
      val density: Density,
      val direction: LayoutDirection,
      val mapState: MapState? = null,
    )

    var value by mutableStateOf("first")
    var observed: Evaluation? = null
    var adapterCreations = 0
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = {
          adapterCreations++
          FakeSnapshotterAdapter()
        }
      )
    try {
      val snapshotter =
        runtime.createSnapshotter(BaseStyle.Empty) {
          val evaluation =
            Evaluation(
              value,
              LocalViewport.current?.size,
              LocalDensity.current,
              LocalLayoutDirection.current,
              LocalMapState.current,
            )
          SideEffect { observed = evaluation }
        }
      snapshotter.capture(
        MapSnapshotRequest(
          30,
          20,
          density = 2f,
          fontScale = 1.5f,
          layoutDirection = LayoutDirection.Rtl,
        )
      )
      assertEquals(
        Evaluation("first", DpSize(30.dp, 20.dp), Density(2f, 1.5f), LayoutDirection.Rtl),
        observed,
      )

      value = "second"
      snapshotter.capture(
        MapSnapshotRequest(
          10,
          40,
          density = 3f,
          fontScale = 2f,
          layoutDirection = LayoutDirection.Ltr,
        )
      )
      assertEquals(
        Evaluation("second", DpSize(10.dp, 40.dp), Density(3f, 2f), LayoutDirection.Ltr),
        observed,
      )
      assertEquals(1, adapterCreations)
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }
}
