package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.mlnffi.MapRenderBackend

class NativeSnapshotRenderTargetTest {
  @Test
  fun openGl_is_selected_only_where_an_offscreen_context_provider_exists() {
    val openGl = setOf(MapRenderBackend.OPENGL)

    assertNotNull(NativeSnapshotRenderTarget.select("linux", openGl))
    assertNotNull(NativeSnapshotRenderTarget.select("windows", openGl))
    assertNull(NativeSnapshotRenderTarget.select("mac os x", openGl))
    assertNull(NativeSnapshotRenderTarget.select("plan 9", openGl))
  }
}
