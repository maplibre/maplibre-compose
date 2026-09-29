package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.onOwner
import org.maplibre.compose.testing.MISSING_ICON_ID
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.missingIconStyle
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position

/** Both engines ask [MapState.missingImageResolver] for an image that the style draws and lacks. */
class MissingImageResolverTest {

  @Test
  fun a_resolved_image_reaches_the_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { id ->
        requests += id
        ResolvedStyleImage.fromPainter(ColorPainter(Color.Red), Density(1f), LayoutDirection.Ltr)
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      val style = assertNotNull(fixture.style)
      fixture.pumpUntil("the resolved image to reach the style", timeout = 20.seconds) {
        style.onOwner { style.imageExists(MISSING_ICON_ID) } == true
      }
      fixture.settle()

      assertEquals(listOf(MISSING_ICON_ID), requests.toList(), "the resolver ran more than once")
      fixture.state.style.images[MISSING_ICON_ID]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
    }
  }

  @Test
  fun an_image_removed_by_the_engine_is_resolved_again(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { id ->
        requests += id
        ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(1, 1)))
      }
      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      val style = assertNotNull(fixture.style)
      fixture.pumpUntil("the first resolution to reach the style") {
        style.onOwner { style.imageExists(MISSING_ICON_ID) } == true
      }
      fixture.settle()

      // Bypass Compose's ownership records, as Native does when evicting unused images.
      style.onOwner { style.removeImage(MISSING_ICON_ID) }
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 4.0))
      fixture.pumpUntil("the removed image to be requested and restored") {
        requests.size >= 2 && style.onOwner { style.imageExists(MISSING_ICON_ID) } == true
      }
      fixture.settle()
      assertEquals(listOf(MISSING_ICON_ID, MISSING_ICON_ID), requests.toList())
    }
  }

  @Test
  fun a_reloaded_style_asks_the_resolver_again(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { id ->
        requests += id
        ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(1, 1)))
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      val firstStyle = assertNotNull(fixture.style)
      fixture.pumpUntil("the first resolution to reach the style", timeout = 20.seconds) {
        firstStyle.onOwner { firstStyle.imageExists(MISSING_ICON_ID) } == true
      }
      fixture.settle()

      // The name differs because the native fixture times out reloading identical style JSON.
      fixture.loadStyle(BaseStyle.Json(missingIconStyle(name = "missing icon again")))
      val reloadedStyle = assertNotNull(fixture.style)
      fixture.pumpUntil("the resolved image to reach the reloaded style", timeout = 20.seconds) {
        reloadedStyle.onOwner { reloadedStyle.imageExists(MISSING_ICON_ID) } == true
      }
      fixture.settle()

      assertEquals(listOf(MISSING_ICON_ID, MISSING_ICON_ID), requests.toList())
      fixture.state.style.images[MISSING_ICON_ID]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
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
      assertNull(
        fixture.state.style.images[MISSING_ICON_ID],
        "a declined image reached the style",
      )

      fixture.state.missingImageResolver = { id ->
        supplied += id
        ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(1, 1)))
      }
      // Each engine asks once per tile parse, so a new zoom is what puts the request in front of
      // the replacement resolver.
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 4.0))
      val style = assertNotNull(fixture.style)
      fixture.pumpUntil(
        "the replacement resolver's image to reach the style",
        timeout = 20.seconds,
      ) {
        style.onOwner { style.imageExists(MISSING_ICON_ID) } == true
      }
      fixture.settle()

      assertEquals(listOf(MISSING_ICON_ID), supplied.toList())
      assertEquals(listOf(MISSING_ICON_ID), declined.toList(), "the replaced resolver ran again")
      fixture.state.style.images[MISSING_ICON_ID]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
    }
  }
}
