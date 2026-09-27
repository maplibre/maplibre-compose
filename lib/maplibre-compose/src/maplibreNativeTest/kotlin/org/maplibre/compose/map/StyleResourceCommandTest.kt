package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
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
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleResourceCommandTest {
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
        val first = ResolvedStyleImage.fromBitmap(callerBitmap)
        callerPixel = 0xff0000ff.toInt()
        val retainedPixel = IntArray(1)
        first.toImageBitmap().readPixels(retainedPixel)
        assertEquals(0xffff0000.toInt(), retainedPixel[0], "preparation owns a pixel copy")
        val replacement = ResolvedStyleImage.fromBitmap(ImageBitmap(2, 1))
        val parked = TestLatch(1)
        val release = TestLatch(1)
        val ownerReleased = CompletableDeferred<Boolean>()
        val session = fixture.session as MlnFfiMapSession
        try {
          assertTrue(
            session.postOwnerTaskForTest {
              parked.countDown()
              ownerReleased.complete(release.await(5_000))
            }
          )
          assertTrue(parked.await(5_000))
          val style = fixture.state.style
          style.images.setAll(mapOf("replaced" to first, "removed" to first))
          style.images.set("replaced", replacement)
          style.images.remove("removed")
          style.sources.add(
            GeoJsonSource(
              "points",
              GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}"""),
              GeoJsonOptions(),
            )
          )
          val queried = async(start = CoroutineStart.UNDISPATCHED) { style.images["replaced"] }
          assertFalse(queried.isCompleted, "query must suspend behind the queued commands")
          assertFalse(
            ownerReleased.isCompleted,
            "commands must return while the owner stays parked",
          )
          release.countDown()
          assertTrue(ownerReleased.await())
          assertNotNull(queried.await())
          assertEquals(null, style.images["removed"])
          assertNotNull(style.sources["points"])
          val binding = assertNotNull(fixture.style) as MlnFfiStyleBinding
          assertEquals(2, binding.readMap { it.styleImageInfo("replaced")?.width })
          assertEquals(1, reads, "reusing prepared pixels must not read the caller bitmap again")
        } finally {
          release.countDown()
        }
      }
    }

  @Test
  fun snapshot_resource_commands_dispatch_even_when_the_worker_scope_names_the_read_dispatcher() =
    runMapTest {
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
        snapshotter.capture(MapSnapshotRequest(8, 8))
        val binding = snapshotter.style.readyLoadedStyle() as MlnFfiStyleBinding
        val holdOwner =
          async(Dispatchers.Default) {
            binding.readMap {
              parked.countDown()
              check(release.await(5_000)) { "snapshot resource command blocked its caller" }
            }
          }
        assertTrue(parked.await(5_000))
        snapshotter.style.images.remove("absent")
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
  fun a_binding_invalidated_after_admission_cannot_read_or_mutate_the_owner_map() = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val actual = fixture.style as MlnFfiStyleBinding
      val prepared = ResolvedStyleImage.fromBitmap(ImageBitmap(1, 1))
      fixture.state.style.images.set("retained", prepared)
      fixture.state.style.awaitCommands()
      for (mutating in listOf(false, true)) {
        var invalidateOnAccess = false
        lateinit var binding: MlnFfiStyleBinding
        binding =
          MlnFfiStyleBinding(
            sessionOpen = { true },
            accessMap = { action ->
              actual.readMap { map ->
                // Models invalidation after an operation was admitted but before its owner
                // callback.
                if (invalidateOnAccess) binding.invalidate()
                action(map)
              }
              true
            },
          )
        invalidateOnAccess = true
        assertFailsWith<IllegalStateException> {
          if (mutating) binding.removeImage("retained") else binding.imageExists("retained")
        }
        assertEquals(true, actual.imageExists("retained"))
      }
    }
  }
}
