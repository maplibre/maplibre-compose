package org.maplibre.compose.layers

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.map.FakeMapAdapter
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.OpRecordingStyleBinding
import org.maplibre.compose.util.ImageStretch

/**
 * An imperative paint write whose expression carries an image registers the image and references
 * its generated id. The write compiles on its caller's thread while the style composition acquires
 * and releases on the host thread; ImageManager's lock serializes that interleaving by
 * construction, so this walk pins the single-threaded behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LayerHandleImagePropertyTest {

  private fun TestScope.mapState() =
    MapState(
      cameraPosition = CameraPosition(),
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      logger = null,
      hostDispatcher = StandardTestDispatcher(testScheduler),
    )

  private class ImageRecordingBinding(baseLayers: List<Layer> = emptyList()) :
    OpRecordingStyleBinding(baseLayers = baseLayers) {
    val images = mutableMapOf<String, ImageBitmap>()
    val properties = mutableListOf<Triple<String, String, JsonElement>>()

    override fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
      images[id] = image
      super.addImage(id, image, sdf, stretch)
    }

    override fun removeImage(id: String) {
      images.remove(id)
      super.removeImage(id)
    }

    override fun setLayerProperty(
      layerId: String,
      name: String,
      value: JsonElement,
      kind: LayerPropertyKind,
    ): Boolean {
      properties.add(Triple(layerId, name, value))
      return super.setLayerProperty(layerId, name, value, kind)
    }
  }

  /** The refusal below is imperative-only; the composition's image path stays. */
  @Test
  fun a_composed_layer_with_an_image_literal_registers_the_image() = runTest {
    val state = mapState()
    state.setStyleComposition {
      BackgroundLayer(id = "bg-user", pattern = image(ImageBitmap(4, 4)))
    }
    val binding = ImageRecordingBinding()
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    state.host.awaitPendingWork()

    assertEquals(1, binding.images.size, "the composed image literal must register")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_paint_write_with_an_image_literal_is_refused_and_a_registered_id_works() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val descriptor = BackgroundLayerDescriptor("bg-base")
    val binding = ImageRecordingBinding(baseLayers = listOf(descriptor))
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    testScheduler.advanceUntilIdle()
    // A literal has no release path outside a composition, so the write is refused.
    val handle = assertNotNull(state.layers["bg-base"])
    val error =
      assertFailsWith<IllegalArgumentException> {
        handle.setPaintProperty("background-pattern", image(ImageBitmap(4, 4)))
      }
    assertTrue(
      "MapState.images" in error.message.orEmpty(),
      "the error names the imperative image channel: ${error.message}",
    )
    assertEquals(0, binding.images.size, "the refused write must register nothing")

    // The imperative image channel plus an id reference serves the same need.
    state.images.add("pattern", ImageBitmap(4, 4))
    handle.setPaintProperty("background-pattern", image("pattern"))
    val (layerId, name, value) = binding.properties.last()
    assertEquals("bg-base", layerId)
    assertEquals("background-pattern", name)
    assertTrue("pattern" in value.toString(), "the property JSON references the id, got $value")

    state.close()
    testScheduler.advanceUntilIdle()
  }
}
