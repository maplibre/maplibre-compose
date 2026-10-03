package org.maplibre.compose.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.onOwner
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.MissingIconId
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.missingIconStyle
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.PreparedImage
import org.maplibre.spatialk.geojson.Position

/** Both engines ask [MapState.missingImageResolver] for an image that the style draws and lacks. */
class MissingImageResolverTest {

  @Test
  fun a_delayed_resolved_image_is_drawn(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requested = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      fixture.state.missingImageResolver = {
        requested.complete(Unit)
        release.await()
        ResolvedStyleImage.fromPainter(ColorPainter(Color.Red), Density(1f), LayoutDirection.Ltr)
      }
      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      fixture.pumpUntil("the missing image request") { requested.isCompleted }
      fixture.settle()
      assertFalse(fixture.readPixel(256, 256).isNear(RgbaPixel(255, 0, 0, 255)))
      release.complete(Unit)
      val style = assertNotNull(fixture.style)
      fixture.pumpUntil("the delayed image to reach the style") {
        style.onOwner { style.imageExists(MISSING_ICON_ID) } == true
      }
      fixture.pumpUntilPixel("the delayed icon to be drawn", 256, 256, RgbaPixel(255, 0, 0, 255))
    }
  }

  @Test
  fun an_image_registered_after_missing_layout_is_drawn(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requested = CompletableDeferred<Unit>()
      fixture.state.missingImageResolver = {
        requested.complete(Unit)
        null
      }
      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      fixture.pumpUntil("the declined missing image request") { requested.isCompleted }
      fixture.settle()
      assertFalse(fixture.readPixel(256, 256).isNear(RgbaPixel(255, 0, 0, 255)))
      val image =
        ResolvedStyleImage.fromPainter(ColorPainter(Color.Red), Density(1f), LayoutDirection.Ltr)
      fixture.state.style.images.set(MISSING_ICON_ID, image)
      fixture.state.style.awaitCommands()
      fixture.pumpUntilPixel(
        "the late registered icon to be drawn",
        256,
        256,
        RgbaPixel(255, 0, 0, 255),
      )
    }
  }

  @Test
  fun a_resolved_image_reaches_the_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { request ->
        requests += request.id
        ResolvedStyleImage.fromPainter(ColorPainter(Color.Red), Density(1f), LayoutDirection.Ltr)
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      val style = assertNotNull(fixture.style)
      fixture.pumpUntil("the resolved image to reach the style", timeout = 20.seconds) {
        style.onOwner { style.imageExists(MissingIconId) } == true
      }
      fixture.settle()

      assertEquals(listOf(MissingIconId), requests.toList(), "the resolver ran more than once")
      fixture.state.style.images[MissingIconId]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
    }
  }

  @Test
  fun an_image_removed_by_the_engine_is_resolved_again(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { request ->
        requests += request.id
        ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(1, 1)))
      }
      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      val style = assertNotNull(fixture.style)
      fixture.pumpUntil("the first resolution to reach the style") {
        style.onOwner { style.imageExists(MissingIconId) } == true
      }
      fixture.settle()

      // Bypass Compose's ownership records, as Native does when evicting unused images.
      style.onOwner { style.removeImage(MissingIconId) }
      fixture.state.setCameraPosition(CameraPosition(center = Position(0.0, 0.0), zoom = 4.0))
      fixture.pumpUntil("the removed image to be requested and restored") {
        requests.size >= 2 && style.onOwner { style.imageExists(MissingIconId) } == true
      }
      fixture.settle()
      assertEquals(listOf(MissingIconId, MissingIconId), requests.toList())
    }
  }

  @Test
  fun a_reloaded_style_asks_the_resolver_again(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val requests = RecordingList<String>()
      fixture.state.missingImageResolver = { request ->
        requests += request.id
        ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(1, 1)))
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      val firstStyle = assertNotNull(fixture.style)
      fixture.pumpUntil("the first resolution to reach the style", timeout = 20.seconds) {
        firstStyle.onOwner { firstStyle.imageExists(MissingIconId) } == true
      }
      fixture.settle()

      // The name differs because the native fixture times out reloading identical style JSON.
      fixture.loadStyle(BaseStyle.Json(missingIconStyle(name = "missing icon again")))
      val reloadedStyle = assertNotNull(fixture.style)
      fixture.pumpUntil("the resolved image to reach the reloaded style", timeout = 20.seconds) {
        reloadedStyle.onOwner { reloadedStyle.imageExists(MissingIconId) } == true
      }
      fixture.settle()

      assertEquals(listOf(MissingIconId, MissingIconId), requests.toList())
      fixture.state.style.images[MissingIconId]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
    }
  }

  @Test
  fun a_replacement_resolver_answers_an_id_the_first_declined(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      val declined = RecordingList<String>()
      val supplied = RecordingList<String>()
      fixture.state.missingImageResolver = { request ->
        declined += request.id
        null
      }

      fixture.loadStyle(BaseStyle.Json(missingIconStyle()))
      fixture.pumpUntil("the missing icon to be declined", timeout = 20.seconds) {
        declined.size == 1
      }
      fixture.settle()
      assertNull(
        fixture.state.style.images[MissingIconId],
        "a declined image reached the style",
      )

      fixture.state.missingImageResolver = { request ->
        supplied += request.id
        ResolvedStyleImage(PreparedImage.fromBitmap(ImageBitmap(1, 1)))
      }
      // Each engine asks once per tile parse, so a new zoom is what puts the request in front of
      // the replacement resolver.
      fixture.state.setCameraPosition(CameraPosition(center = Position(0.0, 0.0), zoom = 4.0))
      val style = assertNotNull(fixture.style)
      fixture.pumpUntil(
        "the replacement resolver's image to reach the style",
        timeout = 20.seconds,
      ) {
        style.onOwner { style.imageExists(MissingIconId) } == true
      }
      fixture.settle()

      assertEquals(listOf(MissingIconId), supplied.toList())
      assertEquals(listOf(MissingIconId), declined.toList(), "the replaced resolver ran again")
      fixture.state.style.images[MissingIconId]!!.asMutable!!.remove()
      fixture.state.style.awaitCommands()
    }
  }
}
