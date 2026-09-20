package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.map.FakeImageBitmap

class StyleImageCacheTest {
  @Test
  fun cancelling_preparation_does_not_strand_another_property_waiting_for_the_same_image() =
    runTest {
      val cache = StyleImageCache()
      var attempts = 0
      val content =
        StyleImageCache.Content(ImageSnapshot.capture(FakeImageBitmap(2, 2)), false, null)
      val request =
        cache.painter("shared") {
          attempts++
          if (attempts == 1) awaitCancellation()
          content
        }
      assertEquals(0, attempts, "evaluating a declaration must not start painter work")
      val first = launch(start = CoroutineStart.UNDISPATCHED) { request.resolve() }
      val second = async(start = CoroutineStart.UNDISPATCHED) { request.resolve() }
      first.cancelAndJoin()
      val definition = second.await()
      assertEquals(definition.id, request.resolve().id)
    }
}
