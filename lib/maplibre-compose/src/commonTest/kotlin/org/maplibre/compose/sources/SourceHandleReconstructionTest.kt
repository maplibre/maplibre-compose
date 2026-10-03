package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertIs
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
  fun published_sources_omit_unsupported_types_and_keep_typed_handles() = runTest {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val adapter = PresentationTestAdapter()
    state.publishPresentation(state.reservePresentation(), adapter)
    val binding = RecordingStyleBinding()
    binding.addSource("clip", buildJsonObject { put("type", "video") })
    binding.addSource("tiles", buildJsonObject { put("type", "vector") })
    try {
      state.styleAuthority.updateLoadedStyle(adapter, binding)
      state.styleAuthority.markStyleReady(adapter)
      assertNull(state.style.sources["clip"])
      assertIs<VectorTileSourceHandle>(state.style.sources["tiles"])
    } finally {
      state.close()
      runtime.close()
    }
  }
}
