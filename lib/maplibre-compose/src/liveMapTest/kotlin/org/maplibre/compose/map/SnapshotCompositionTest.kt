package org.maplibre.compose.map

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GroundScaleGlobalState
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.groundScale
import org.maplibre.compose.testing.setImage
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SnapshotCompositionTest {
  @Test
  fun repeated_captures_update_declared_content_and_release_its_ids() = runTest {
    val binding = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = {
          FakeSnapshotterAdapter(
            prepare = { _, _ -> binding },
            capture = { request, revision ->
              reconciler.apply(binding, revision)
              FakeImageBitmap(request.extent().width, request.extent().height)
            },
          )
        }
      )
    val source =
      GeoJsonSource(
        "features",
        GeoJsonData.Features(featureCollectionOf()),
      )
    val bitmap = ImageBitmap(1, 1)
    var declared by mutableStateOf(true)
    var visible by mutableStateOf(true)
    try {
      val snapshotter =
        runtime.createSnapshotter(BaseStyle.Empty) {
          if (declared) SymbolLayer("pin", source, visible = visible, iconImage = image(bitmap))
        }
      val request = MapSnapshotRequest(DpSize(4.dp, 4.dp))
      snapshotter.capture(request.size)
      val sourceHandle = assertNotNull(snapshotter.style.sources[source])
      val layerHandle = assertNotNull(snapshotter.style.layers["pin"])
      val imageId = binding.imageIds.single()
      val imageHandle = assertNotNull(snapshotter.style.images[imageId])
      assertNull(sourceHandle.asMutable)
      assertNull(layerHandle.asMutable)
      assertNull(imageHandle.asMutable)
      assertFailsWith<IllegalStateException> { snapshotter.style.sources.add(source) }
      assertFailsWith<IllegalStateException> { snapshotter.style.images.remove(imageId) }
      assertFailsWith<IllegalStateException> { snapshotter.style.setImage(imageId, bitmap) }

      snapshotter.capture(request.size)
      assertSame(sourceHandle, snapshotter.style.sources[source])
      assertSame(layerHandle, snapshotter.style.layers["pin"])
      visible = false
      snapshotter.capture(request.size)
      assertEquals(
        JsonPrimitive("none"),
        snapshotter.style.layers["pin"]?.getProperty("visibility"),
      )

      declared = false
      snapshotter.capture(request.size)
      assertNull(snapshotter.style.sources[source])
      assertNull(snapshotter.style.layers["pin"])
      assertNull(snapshotter.style.images[imageId])
      sourceHandle.resetFeatureStates()
      assertNull(layerHandle.getProperty("visibility"))
      assertNull(imageHandle.asMutable)
      snapshotter.style.sources.add(source)
      snapshotter.style.setImage(imageId, bitmap)
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun a_capture_writes_the_ground_scale_of_its_camera() = runTest {
    val binding = RecordingStyleBinding()
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = { FakeSnapshotterAdapter(prepare = { _, _ -> binding }) }
      )
    try {
      val snapshotter = runtime.createSnapshotter(BaseStyle.Empty) {}
      snapshotter.capture(DpSize(4.dp, 4.dp)) {
        cameraPosition = CameraPosition(center = Position(0.0, 60.0))
      }
      assertEquals(
        JsonPrimitive(groundScale(latitude = 60.0)),
        binding.globalStateValues[GroundScaleGlobalState],
      )
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }

  @Test
  fun a_throwing_anchor_predicate_fails_the_capture() = runTest {
    val binding = RecordingStyleBinding(layers = listOf(TestLayer("base", "background")))
    val reconciler = StyleReconciler()
    val runtime =
      mapRuntimeForTest(
        createSnapshotterAdapter = {
          FakeSnapshotterAdapter(
            prepare = { _, _ -> binding },
            capture = { request, revision ->
              reconciler.apply(binding, revision)
              FakeImageBitmap(request.extent().width, request.extent().height)
            },
          )
        }
      )
    try {
      val snapshotter =
        runtime.createSnapshotter(BaseStyle.Empty) {
          Anchor.Above({ error("bad predicate") }) { BackgroundLayer("over", visible = true) }
        }
      val thrown =
        assertFailsWith<MapSnapshotException> {
          snapshotter.capture(DpSize(4.dp, 4.dp))
        }
      // Coroutines on the JVM can rethrow a copy that has the original exception as its cause.
      assertTrue(
        generateSequence<Throwable>(thrown) { it.cause }
          .any { it is IllegalStateException && it.message == "bad predicate" },
        "Thrown: ${thrown.stackTraceToString()}",
      )
      assertEquals(listOf("base"), binding.layerIds())
    } finally {
      runtime.close()
      runtime.awaitClosed()
    }
  }

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
      snapshotter.capture(DpSize(4.dp, 4.dp))
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
      GeoJsonSource(
        "features",
        GeoJsonData.Features(featureCollectionOf()),
      )
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
      val bitmap = snapshotter.capture(DpSize(4.dp, 4.dp))
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
      snapshotter.capture(DpSize(30.dp, 20.dp)) {
        density = Density(2f, 1.5f)
        layoutDirection = LayoutDirection.Rtl
      }
      assertEquals(
        Evaluation("first", DpSize(30.dp, 20.dp), Density(2f, 1.5f), LayoutDirection.Rtl),
        observed,
      )

      value = "second"
      snapshotter.capture(DpSize(10.dp, 40.dp)) {
        density = Density(3f, 2f)
        layoutDirection = LayoutDirection.Ltr
      }
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
