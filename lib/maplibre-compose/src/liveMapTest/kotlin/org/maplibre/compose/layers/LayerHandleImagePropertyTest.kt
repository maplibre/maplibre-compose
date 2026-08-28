package org.maplibre.compose.layers

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
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
import org.maplibre.compose.map.RecordingHostDispatcher
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
      hostDispatcher = RecordingHostDispatcher(StandardTestDispatcher(testScheduler)),
    )

  private class ImageRecordingBinding(baseLayers: List<Layer>) :
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
    ) {
      properties.add(Triple(layerId, name, value))
      super.setLayerProperty(layerId, name, value, kind)
    }
  }

  @Test
  fun a_paint_write_with_an_image_expression_registers_the_image() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val descriptor = BackgroundLayerDescriptor("bg-base")
    val binding = ImageRecordingBinding(baseLayers = listOf(descriptor))
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    testScheduler.advanceUntilIdle()
    descriptor.bindExisting(binding)

    val handle = assertNotNull(state.layers["bg-base"])
    handle.setPaintProperty("background-pattern", image(ImageBitmap(4, 4)))

    assertEquals(1, binding.images.size, "the image write must register the bitmap")
    val imageId = binding.images.keys.single()
    val (layerId, name, value) = binding.properties.last()
    assertEquals("bg-base", layerId)
    assertEquals("background-pattern", name)
    assertTrue(
      imageId in value.toString(),
      "the property JSON must reference the generated image id, got $value",
    )

    state.close()
    testScheduler.advanceUntilIdle()
  }
}
