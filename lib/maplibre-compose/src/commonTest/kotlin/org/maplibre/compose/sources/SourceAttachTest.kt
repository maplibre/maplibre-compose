package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleMutationException

class SourceInstallTest {

  private fun vectorSource(id: String) = VectorSource(id, "https://example.invalid/{z}/{x}/{y}.pbf")

  @Test
  fun a_repeat_install_of_the_same_id_is_refused() {
    val binding = RecordingStyleBinding()
    val source = vectorSource("shared")
    source.install(binding)
    assertTrue("shared" in binding.sources)
    assertFailsWith<IllegalStateException> { vectorSource("shared").install(binding) }
  }

  @Test
  fun a_synchronous_unload_during_install_does_not_store_a_binding_on_the_source() {
    val binding =
      object : RecordingStyleBinding() {
        override fun addSource(sourceId: String, source: JsonObject): Boolean {
          super.addSource(sourceId, source)
          unload()
          return true
        }
      }
    val source = vectorSource("sync-unload")
    source.install(binding)
    assertTrue(source.map == null)
  }

  @Test
  fun a_refused_add_names_the_source() {
    val binding =
      object : RecordingStyleBinding() {
        override fun addSource(sourceId: String, source: JsonObject): Boolean {
          throw StyleMutationException("engine refused", null)
        }
      }
    val error = assertFailsWith<IllegalStateException> { vectorSource("bad").install(binding) }
    assertTrue("bad" in error.message.orEmpty())
  }
}
