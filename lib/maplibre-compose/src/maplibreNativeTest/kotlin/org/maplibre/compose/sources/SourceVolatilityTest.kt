package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.addSource
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class SourceVolatilityTest {
  @Test
  fun queued_writes_do_not_block_or_change_a_replacement_with_the_same_id() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val source =
        VectorTileSource(
          "tiles",
          listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
          TileSetOptions(),
        )
      binding.addSource(source.definition())
      val parked = TestLatch(1)
      val release = TestLatch(1)
      val finished = TestLatch(1)
      try {
        assertTrue(
          fixture.session.postOwnerTaskForTest {
            parked.countDown()
            release.await(5_000L)
            finished.countDown()
          }
        )
        assertTrue(parked.await(5_000L), "native owner did not reach the gate")
        binding.setSourceVolatile(source.id, true)
        assertTrue(
          fixture.session.postOwnerTaskForTest {
            assertEquals(true, binding.readMap { it.styleSourceInfo(source.id)?.volatileSource })
            binding.removeSource(source.id)
            binding.addSource(source.definition())
          }
        )
        // This command belongs to the old installation until queued removal actually commits.
        binding.setSourceVolatile(source.id, true)
        assertEquals(1L, finished.count, "source write waited for the native owner")
      } finally {
        release.countDown()
      }
      assertEquals(false, binding.awaitMap { it.styleSourceInfo(source.id)?.volatileSource })
    }
  }

  @Test
  fun volatility_updates_the_live_source_and_rejects_a_removed_handle(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        val source =
          VectorTileSource(
            "tiles",
            listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
            TileSetOptions(),
          )
        fixture.state.style.addSource(source)
        val handle = assertNotNull(fixture.state.style.sources[source.id])
        assertEquals(false, handle.isVolatile())
        val mutable = assertNotNull(handle.asMutable)
        mutable.setVolatile(true)
        // A separately acquired handle must see native state, rather than a handle-local copy.
        assertEquals(true, assertNotNull(fixture.state.style.sources[source.id]).isVolatile())
        mutable.setVolatile(false)
        assertEquals(false, handle.isVolatile())
        fixture.state.style.sources[source.id]!!.asMutable!!.remove()
        fixture.state.style.awaitCommands()
        assertFailsWith<IllegalStateException> { handle.isVolatile() }
        assertFailsWith<IllegalStateException> { mutable.setVolatile(true) }
      }
    }
}
