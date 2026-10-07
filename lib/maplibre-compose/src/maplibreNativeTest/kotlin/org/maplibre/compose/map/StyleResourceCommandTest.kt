package org.maplibre.compose.map

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogRecord
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.implementation
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.readMap
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.PreparedImage
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.spatialk.geojson.Position

class StyleResourceCommandTest {
  @Test
  fun layer_and_global_writes_return_while_the_owner_is_busy_and_reads_follow_them() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"sources":{},"layers":[{"id":"background","type":"background"}]}"""
        )
      )
      val layer = assertNotNull(fixture.state.style.layers["background"])
      val mutable = assertNotNull(layer.asMutable)
      val state = fixture.state.style.globalState
      val parked = TestLatch(1)
      val release = TestLatch(1)
      try {
        (fixture.session as MlnFfiMapSession).loop.submit {
          parked.countDown()
          check(release.await(5_000)) { "style write blocked its caller" }
        }
        assertTrue(parked.await(5_000))
        mutable.setPaintProperty("background-opacity", JsonPrimitive(0.25))
        mutable.setPaintProperty("background-opacity", JsonPrimitive(0.75))
        state.setProperty("value", JsonPrimitive(1))
        state.setProperty("value", JsonPrimitive(2))
        val read =
          async(start = CoroutineStart.UNDISPATCHED) {
            layer.getProperty("background-opacity")
          }
        val globalRead = async(start = CoroutineStart.UNDISPATCHED) { state.get() }
        assertFalse(read.isCompleted)
        assertFalse(globalRead.isCompleted)
        release.countDown()
        assertEquals(JsonPrimitive(0.75), read.await())
        assertEquals(JsonObject(mapOf("value" to JsonPrimitive(2))), globalRead.await())
      } finally {
        release.countDown()
      }
    }
  }

  @Test
  fun prepared_commands_return_while_the_owner_is_busy_and_queries_observe_submission_order() =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        var reads = 0
        var callerPixel = 0xffff0000.toInt()
        val callerBitmap =
          object : ImageBitmap by ImageBitmap(1, 1) {
            override fun readPixels(
              buffer: IntArray,
              startX: Int,
              startY: Int,
              width: Int,
              height: Int,
              bufferOffset: Int,
              stride: Int,
            ) {
              reads++
              buffer[bufferOffset] = callerPixel
            }
          }
        val first = ResolvedStyleImage(PreparedImage.fromBitmap(callerBitmap))
        callerPixel = 0xff0000ff.toInt()
        val retainedPixel = IntArray(1)
        first.image.toImageBitmap().readPixels(retainedPixel)
        assertEquals(0xffff0000.toInt(), retainedPixel[0], "preparation owns a pixel copy")
        val replacement = ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(2, 1)))
        val parked = TestLatch(1)
        val release = TestLatch(1)
        val ownerReleased = CompletableDeferred<Boolean>()
        val session = fixture.session as MlnFfiMapSession
        try {
          session.loop.submit {
            parked.countDown()
            ownerReleased.complete(release.await(5_000))
          }

          assertTrue(parked.await(5_000))
          val style = fixture.state.style
          style.images.setAll(mapOf("replaced" to first, "removed" to first))
          style.images.set("replaced", replacement)
          style.images.remove("removed")
          val added =
            async(start = CoroutineStart.UNDISPATCHED) {
              style.sources.add(
                GeoJsonSource(
                  "points",
                  GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
                  GeoJsonOptions(),
                )
              )
            }
          val queried = async(start = CoroutineStart.UNDISPATCHED) { style.images["replaced"] }
          assertFalse(queried.isCompleted, "query must suspend behind the queued commands")
          assertFalse(added.isCompleted, "the add must suspend behind the queued commands")
          assertFalse(
            ownerReleased.isCompleted,
            "commands must return while the owner stays parked",
          )
          release.countDown()
          assertTrue(ownerReleased.await())
          assertNotNull(queried.await())
          assertEquals(null, style.images["removed"])
          assertNotNull(added.await())
          val binding = assertNotNull(fixture.style) as MlnFfiStyleBinding
          assertEquals(2, binding.readMap { it.styleImageInfo("replaced")?.width })
          assertEquals(1, reads, "reusing prepared pixels must not read the caller bitmap again")
        } finally {
          release.countDown()
        }
      }
    }

  @Test
  fun snapshot_resource_commands_return_while_the_owner_is_busy() = runMapTest {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      createNativeMapRuntime(
        MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
      )
    val snapshotter = runtime.createSnapshotter(BaseStyle.Empty)
    val parked = TestLatch(1)
    val release = TestLatch(1)
    try {
      snapshotter.capture(MapSnapshotRequest(DpSize(8.dp, 8.dp)))
      val binding = snapshotter.style.readyLoadedStyle() as MlnFfiStyleBinding
      val imageSource =
        checkNotNull(snapshotter.style.sources.add(ImageSource("image", Quad, image(OpaqueRed))))
      val holdOwner =
        async(Dispatchers.Default) {
          binding.readMap {
            parked.countDown()
            check(release.await(5_000)) { "snapshot resource command blocked its caller" }
          }
        }
      assertTrue(parked.await(5_000))
      snapshotter.style.images.remove("absent")
      // Snapshotter handles are not confined to the main thread.
      withContext(Dispatchers.Default) { imageSource.setImage(image(OpaqueGreen)) }
      release.countDown()
      holdOwner.await()
      snapshotter.style.awaitCommands()
    } finally {
      release.countDown()
      snapshotter.close()
      snapshotter.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  @Test
  fun image_source_writes_return_while_the_owner_is_busy_and_apply_in_call_order() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val handle =
        checkNotNull(fixture.state.style.sources.add(ImageSource("image", Quad, image(OpaqueRed))))
      val parked = TestLatch(1)
      val release = TestLatch(1)
      val ownerReleased = CompletableDeferred<Boolean>()
      recordingLogs { records ->
        try {
          (fixture.session as MlnFfiMapSession).loop.submit {
            parked.countDown()
            ownerReleased.complete(release.await(5_000))
          }
          assertTrue(parked.await(5_000))
          handle.setImage(image(OpaqueGreen))
          handle.setUri("https://example.invalid/image.png")
          handle.setImage(image(OpaqueRed))
          handle.setBounds(Moved)
          assertFalse(ownerReleased.isCompleted, "writes must return while the owner stays parked")
        } finally {
          release.countDown()
        }
        assertTrue(ownerReleased.await())
        assertEquals(Moved.corners(), binding.readMap { it.imageSourceCorners("image") })
        assertEquals(emptyList(), records.problems())
      }
    }
  }

  @Test
  fun a_source_write_whose_style_unloads_mid_task_is_dropped() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val handle =
        checkNotNull(fixture.state.style.sources.add(ImageSource("image", Quad, image(OpaqueRed))))
      val ran = TestLatch(1)
      recordingLogs { records ->
        // The unload lands after the task passed its loaded and identity checks.
        handle.implementation.definitionOperation {
          binding.invalidate()
          try {
            binding.setImageSourceImage("image", image(OpaqueGreen))
          } finally {
            ran.countDown()
          }
        }
        assertTrue(ran.await(5_000))
        val drained = TestLatch(1)
        (fixture.session as MlnFfiMapSession).loop.submit { drained.countDown() }
        assertTrue(drained.await(5_000))
        // The dropped write is logged as skipped, not as an engine failure.
        assertEquals(
          listOf("Source 'image' was not written: the loaded style changed first"),
          records.problems(),
        )
      }
    }
  }

  @Test
  fun a_write_queued_behind_a_removal_does_not_reach_a_same_id_replacement() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val style = fixture.state.style
      val handle = checkNotNull(style.sources.add(ImageSource("image", Quad, image(OpaqueRed))))
      val parked = TestLatch(1)
      val release = TestLatch(1)
      recordingLogs { records ->
        try {
          (fixture.session as MlnFfiMapSession).loop.submit {
            parked.countDown()
            release.await(5_000)
          }
          assertTrue(parked.await(5_000))
          handle.remove()
          handle.setBounds(Moved)
          handle.setImage(image(OpaqueGreen))
          val replacement =
            async(start = CoroutineStart.UNDISPATCHED) {
              style.sources.add(ImageSource("image", Quad, image(OpaqueRed)))
            }
          release.countDown()
          replacement.await()
        } finally {
          release.countDown()
        }
        assertEquals(Quad.corners(), binding.readMap { it.imageSourceCorners("image") })
        assertEquals(emptyList(), records.problems())
      }
    }
  }

  @Test
  fun style_metadata_capture_finishes_when_the_session_has_logically_closed() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"sources":{},"layers":[{"id":"background","type":"background"}]}"""
        )
      )
      val actual = fixture.style as MlnFfiStyleBinding
      val binding =
        checkNotNull(
          actual.readMap { map ->
            MlnFfiStyleBinding(map = map, loop = actual.loop, sessionOpen = { false })
          }
        )
      assertEquals(listOf("background"), binding.baseLayers.map { it.id })
      assertFalse(binding.isLoaded)
      assertFailsWith<IllegalStateException> { actual.readMap { binding.layerIds() } }
    }
  }

  @Test
  fun synchronous_binding_calls_run_only_on_the_owner_and_only_while_loaded() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val actual = fixture.style as MlnFfiStyleBinding
      val prepared = ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(1, 1)))
      fixture.state.style.images.set("retained", prepared)
      fixture.state.style.awaitCommands()

      val offOwner = assertFailsWith<IllegalStateException> { actual.imageExists("retained") }
      assertTrue("owner thread" in offOwner.message.orEmpty(), offOwner.message)
      assertFailsWith<IllegalStateException> { actual.removeImage("retained") }
      assertFailsWith<IllegalStateException> { actual.globalState() }
      assertFailsWith<IllegalStateException> {
        actual.setGlobalStateProperty("value", JsonPrimitive(1))
      }
      assertFailsWith<IllegalStateException> {
        actual.layerProperty("missing", "background-opacity")
      }
      assertFailsWith<IllegalStateException> { actual.featureState("missing", null, "1") }
      assertEquals(true, actual.awaitOwner { actual.imageExists("retained") })

      for (mutating in listOf(false, true)) {
        val binding =
          checkNotNull(
            actual.readMap { map ->
              MlnFfiStyleBinding(map = map, loop = actual.loop, sessionOpen = { true })
            }
          )
        binding.invalidate()
        assertFailsWith<IllegalStateException> {
          actual.readMap {
            if (mutating) binding.removeImage("retained") else binding.imageExists("retained")
          }
        }
        assertEquals(true, actual.readMap { actual.imageExists("retained") })
      }
    }
  }

  private fun image(pixel: Int): PreparedImage =
    PreparedImage.fromBitmap(ImageBitmap(1, 1).also { it.fill(pixel) })

  private fun ImageBitmap.fill(pixel: Int) {
    Canvas(this).drawRect(Rect(0f, 0f, 1f, 1f), Paint().apply { color = Color(pixel) })
  }

  private fun PositionQuad.corners(): List<Pair<Double, Double>> =
    listOf(topLeft, topRight, bottomRight, bottomLeft).map { it.longitude to it.latitude }

  private fun MapHandle.imageSourceCorners(id: String): List<Pair<Double, Double>>? =
    imageSourceCoordinates(id)?.map { it.longitude to it.latitude }

  /** Records what the library logs while [block] runs. */
  private inline fun recordingLogs(block: (RecordingList<MapLogRecord>) -> Unit) {
    val records = RecordingList<MapLogRecord>()
    val previous = MapLogging.logger
    MapLogging.logger = MapLogger { records += it }
    try {
      block(records)
    } finally {
      MapLogging.logger = previous
    }
  }

  /** The library logs a failed owner task as an error and a rejected source write as a warning. */
  private fun List<MapLogRecord>.problems(): List<String> = filter {
    it.source == MapLogSource.Library && it.level >= MapLogLevel.Warning
  }
    .map { it.message }

  private companion object {
    const val OpaqueRed = 0xffff0000.toInt()
    const val OpaqueGreen = 0xff00ff00.toInt()

    val Quad =
      PositionQuad(
        Position(-1.0, 1.0),
        Position(1.0, 1.0),
        Position(1.0, -1.0),
        Position(-1.0, -1.0),
      )
    val Moved = Quad.copy(topLeft = Position(-2.0, 1.0))
  }
}
