package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.ImageManager
import org.maplibre.compose.style.OpRecordingStyleBinding
import org.maplibre.compose.util.ImageStretch

/** Models the engines, which replace an image on a second add of its id. */
private class UpsertingImageBinding : OpRecordingStyleBinding() {
  val images = mutableMapOf<String, ImageBitmap>()

  override fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
    op("addImage:$id")
    images[id] = image
  }

  override fun removeImage(id: String) {
    op("removeImage:$id")
    images.remove(id)
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StyleImagesTest {

  private fun TestScope.mapState() =
    MapState(
      cameraPosition = CameraPosition(),
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      logger = null,
      hostDispatcher = StandardTestDispatcher(testScheduler),
    )

  private fun TestScope.attach(state: MapState, binding: UpsertingImageBinding) {
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_re_add_of_an_own_id_replaces_the_pixels() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val binding = UpsertingImageBinding()
    attach(state, binding)

    val first = ImageBitmap(2, 2)
    val second = ImageBitmap(2, 2)
    state.images.add("star", first)
    state.images.add("star", second)

    assertSame(second, binding.images["star"])
    assertEquals(listOf("star"), state.images.ids)

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun the_reserved_prefix_is_refused() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    attach(state, UpsertingImageBinding())

    assertFailsWith<IllegalArgumentException> {
      state.images.add("__MAPLIBRE_COMPOSE_bitmap_0", ImageBitmap(2, 2))
    }
    assertTrue(state.images.ids.isEmpty())

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun ids_reflect_each_op_without_recomposition() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    attach(state, UpsertingImageBinding())

    state.images.add("star", ImageBitmap(2, 2))
    assertEquals(listOf("star"), state.images.ids)
    state.images.add("dot", ImageBitmap(2, 2))
    assertEquals(listOf("star", "dot"), state.images.ids)
    state.images.remove("star")
    assertEquals(listOf("dot"), state.images.ids)

    assertFailsWith<IllegalArgumentException> { state.images.remove("star") }

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun ops_on_a_detached_or_closed_state_throw() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    testScheduler.advanceUntilIdle()

    assertFailsWith<IllegalStateException> { state.images.add("star", ImageBitmap(2, 2)) }
    assertFailsWith<IllegalStateException> { state.images.remove("star") }

    state.close()
    testScheduler.advanceUntilIdle()
    assertFailsWith<IllegalStateException> { state.images.add("star", ImageBitmap(2, 2)) }
  }

  @Test
  fun a_base_style_reload_drops_the_registered_images() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val first = UpsertingImageBinding()
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, first)
    testScheduler.advanceUntilIdle()

    state.images.add("star", ImageBitmap(2, 2))
    assertEquals(listOf("star"), state.images.ids)

    val second = UpsertingImageBinding()
    first.unload()
    state.callbacks.onStyleChanged(adapter, second)
    testScheduler.advanceUntilIdle()

    assertTrue(state.images.ids.isEmpty(), "the registration drops with the style")
    assertTrue(second.images.isEmpty(), "the new style never receives the dropped image")

    state.images.add("star", ImageBitmap(2, 2))
    assertEquals(listOf("star"), state.images.ids)

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_composition_release_of_equal_content_never_frees_an_app_image() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val binding = UpsertingImageBinding()
    attach(state, binding)

    val bitmap = ImageBitmap(2, 2)
    state.images.add("star", bitmap)

    val manager = state.styleNode.imageManager
    val key = ImageManager.BitmapKey(bitmap, isSdf = false, stretch = null)
    val generatedId = manager.acquireBitmap(key)
    assertTrue(generatedId != "star", "the composition registers under its own generated id")
    manager.releaseBitmap(ImageManager.BitmapKey(bitmap, isSdf = false, stretch = null))

    assertSame(bitmap, binding.images["star"], "the app image survives the equal-content release")
    assertEquals(listOf("star"), state.images.ids)
    assertTrue(generatedId !in binding.images, "the composition's own registration was freed")

    state.close()
    testScheduler.advanceUntilIdle()
  }
}
