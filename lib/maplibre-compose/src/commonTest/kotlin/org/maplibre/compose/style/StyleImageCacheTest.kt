package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.map.FakeImageBitmap

class StyleImageCacheTest {
  @Test
  fun equal_pixels_share_an_image_until_the_last_reference_leaves() {
    val cache = StyleImageCache()
    val content = StyleImageCache.Content(ImageSnapshot.capture(FakeImageBitmap(2, 2)), false, null)
    val first = cache.bitmap("first") { content }
    val second =
      cache.bitmap("second") { content.copy(image = ImageSnapshot.capture(FakeImageBitmap(2, 2))) }
    assertSame(first.definition, second.definition)
    val node =
      StyleImageNode().apply {
        request = second
        definition = second.definition
      }
    cache.retain(listOf(node))
    val third = cache.bitmap("third") { content }
    assertSame(second.definition, third.definition)
    val sdf = cache.bitmap("sdf") { content.copy(sdf = true) }
    assertNotEquals(second.definition!!.id, sdf.definition!!.id)
    cache.retain(emptyList())
    val replacement = cache.bitmap("second") { content }
    assertNotEquals(second.definition!!.id, replacement.definition!!.id)
  }

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
      assertSame(definition, request.resolve())
      assertEquals(2, attempts, "one cancelled attempt, followed by one shared successful result")
    }
}
