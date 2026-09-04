package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MISSING_ICON_ID
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.missingIconStyle
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Position

/** Both engines ask [MapState.missingImageResolver] for an image that the style draws and lacks. */
class MissingImageResolverTest {

  @Test
  fun a_resolved_image_reaches_the_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      val firstRequest = CompletableDeferred<String>()
      fixture.state.missingImageResolver = { id ->
        requests += id
        firstRequest.complete(id)
        ResolvedStyleImage(ImageBitmap(1, 1))
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      fixture.pumpUntil("the missing icon to be requested", timeout = 20.seconds) {
        firstRequest.isCompleted
      }
      assertEquals(MISSING_ICON_ID, firstRequest.await())
      fixture.settle()

      assertEquals(listOf(MISSING_ICON_ID), requests.toList(), "the resolver ran more than once")
      assertTrue(
        fixture.state.style.images.remove(MISSING_ICON_ID),
        "the resolved image did not reach the style",
      )
    }
  }

  @Test
  fun a_reloaded_style_asks_the_resolver_again(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { id ->
        requests += id
        ResolvedStyleImage(ImageBitmap(1, 1))
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      fixture.pumpUntil("the missing icon to be requested", timeout = 20.seconds) {
        requests.size == 1
      }
      fixture.settle()

      // The name differs because the native fixture times out reloading identical style JSON.
      fixture.loadStyle(BaseStyle.Json(missingIconStyle(name = "missing icon again")))
      fixture.pumpUntil("the reloaded style to ask for the missing icon", timeout = 20.seconds) {
        requests.size == 2
      }
      fixture.settle()

      assertEquals(listOf(MISSING_ICON_ID, MISSING_ICON_ID), requests.toList())
      assertTrue(
        fixture.state.style.images.remove(MISSING_ICON_ID),
        "the resolved image did not reach the reloaded style",
      )
    }
  }

  @Test
  fun a_replacement_resolver_answers_an_id_the_first_declined(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val declined = RecordingList<String>()
      val supplied = RecordingList<String>()
      fixture.state.missingImageResolver = { id ->
        declined += id
        null
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      fixture.pumpUntil("the missing icon to be declined", timeout = 20.seconds) {
        declined.size == 1
      }
      fixture.settle()
      assertFalse(
        fixture.state.style.images.remove(MISSING_ICON_ID),
        "a declined image reached the style",
      )

      fixture.state.missingImageResolver = { id ->
        supplied += id
        ResolvedStyleImage(ImageBitmap(1, 1))
      }
      // Each engine asks once per tile parse, so a new zoom is what puts the request in front of
      // the replacement resolver.
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 4.0))
      fixture.pumpUntil("the replacement resolver to be asked", timeout = 20.seconds) {
        supplied.size == 1
      }
      fixture.settle()

      assertEquals(listOf(MISSING_ICON_ID), supplied.toList())
      assertEquals(listOf(MISSING_ICON_ID), declined.toList(), "the replaced resolver ran again")
      assertTrue(
        fixture.state.style.images.remove(MISSING_ICON_ID),
        "the resolved image did not reach the style",
      )
    }
  }
}
