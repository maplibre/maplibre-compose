package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.RecordingStyleBinding

class SourceAttachTest {

  private fun vectorSource(id: String) = VectorSource(id, "https://example.invalid/{z}/{x}/{y}.pbf")

  @Test
  fun a_repeat_attach_to_the_same_style_is_a_no_op() {
    val binding = RecordingStyleBinding()
    val source = vectorSource("shared")

    source.attach(binding)
    source.attach(binding)

    assertTrue(source.isAttached)
    assertTrue("shared" in binding.sources)
  }

  @Test
  fun a_synchronous_unload_during_attach_does_not_reenter_the_lock() {
    val binding =
      object : RecordingStyleBinding() {
        override fun addSource(sourceId: String, source: JsonObject): Boolean {
          super.addSource(sourceId, source)
          unload()
          return true
        }
      }
    val source = vectorSource("sync-unload")
    source.attach(binding)
    assertTrue(!source.isAttached)
  }

  @Test
  fun a_different_descriptor_with_the_same_id_is_refused() {
    val binding = RecordingStyleBinding()
    vectorSource("shared").attach(binding)

    assertFailsWith<IllegalStateException> { vectorSource("shared").attach(binding) }
  }
}
