package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.map.PresentationTestAdapter
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding

class SourceHandleReconstructionTest {
  @Test
  fun a_source_type_without_a_handle_interface_gets_a_plain_handle() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val adapter = PresentationTestAdapter()
    state.publishPresentation(state.reservePresentation(), adapter)
    val binding = RecordingStyleBinding()
    val clipJson = buildJsonObject {
      put("type", "video")
      put("attribution", "© clip")
    }
    binding.addSource("clip", clipJson)
    binding.addSource("tiles", buildJsonObject { put("type", "vector") })
    try {
      state.styleAuthority.updateLoadedStyle(adapter, binding)
      state.styleAuthority.markStyleReady(adapter)
      val clip = assertIs<UnmodeledSourceHandleImpl>(state.style.sources["clip"])
      assertEquals("© clip", clip.attributionHtml)
      assertIs<VectorTileSourceHandle>(state.style.sources["tiles"])

      assertNotNull(clip.asMutable).remove()
      state.style.awaitCommands()
      assertNull(state.style.sources["clip"])
      val added = checkNotNull(state.style.sources.add(reconstructedSource("clip", clipJson)))
      assertEquals("clip", added.id)
      assertIs<UnmodeledSourceHandleImpl>(state.style.sources["clip"])
    } finally {
      state.close()
      runtime.close()
    }
  }
}
