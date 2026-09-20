package org.maplibre.compose.style

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.map.FakeImageBitmap

class StyleImageCacheConcurrencyTest {
  @Test
  fun concurrent_painters_share_equal_images_and_keep_distinct_images_separate() = runBlocking {
    Executors.newFixedThreadPool(8).asCoroutineDispatcher().use { dispatcher ->
      repeat(64) {
        val cache = StyleImageCache()
        val barrier = CyclicBarrier(8)
        val contents =
          List(4) {
            StyleImageCache.Content(ImageSnapshot.capture(FakeImageBitmap(it + 1, 1)), false, null)
          }
        val requests =
          List(8) { index ->
            cache.painter(index) {
              barrier.await(10, TimeUnit.SECONDS)
              contents[index % contents.size]
            }
          }
        val definitions = requests.map { async(dispatcher) { it.resolve() } }.awaitAll()
        assertEquals(contents.size, definitions.map { it.id }.distinct().size)
        definitions.forEachIndexed { index, definition ->
          assertEquals(contents[index % contents.size].image, definition.image)
          assertEquals(definitions[index % contents.size].id, definition.id)
        }
      }
    }
  }
}
