package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleHandleOperationGuard

class SourceHandleReconstructionTest {

  @Test
  fun an_omitted_engine_source_does_not_get_a_handle() {
    val style = RecordingStyleBinding()
    style.addSource("clip", buildJsonObject { put("type", "video") })

    assertNull(style.getSource("clip"))
    assertNull(style.handle("clip"))
  }

  @Test
  fun a_reconstructed_vector_source_gets_a_typed_handle() {
    val style = RecordingStyleBinding()
    style.addSource("tiles", buildJsonObject { put("type", "vector") })

    assertIs<VectorTileSourceHandle>(style.handle("tiles"))
  }

  private fun RecordingStyleBinding.handle(id: String): SourceHandle? =
    sourceHandle(
      id = id,
      definition = null,
      currentDefinition = { null },
      isCurrentResource = { true },
      operations = ImmediateOperations,
    )

  private object ImmediateOperations : StyleHandleOperationGuard {
    override fun <T> run(action: () -> T): T = action()

    override fun requireSourceWritable(id: String) {}

    override fun requireLayerWritable(id: String) {}
  }
}
