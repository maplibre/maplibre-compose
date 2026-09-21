package org.maplibre.compose.map

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleReconciler

@OptIn(ExperimentalTestApi::class)
class StyleBeforePresentationTest {
  @Test
  fun a_loaded_engine_receives_content_before_its_presentation_is_published() =
    runPlainComposeUiTest {
      val style = RecordingStyleBinding()
      val reconciler = StyleReconciler()
      val adapter =
        object : PresentationTestAdapter() {
          override suspend fun reconcileStyleRevision(revision: DesiredStyleRevision) =
            reconciler.apply(style, revision)
        }
      lateinit var state: MapState
      lateinit var presentation: MapPresentationBinding
      setContent {
        val runtime = remember { mapRuntimeForTest() }
        DisposableEffect(runtime) { onDispose { runtime.close() } }
        state = remember {
          runtime.createMapState(BaseStyle.Empty) { BackgroundLayer("content", visible = true) }
        }
        val owner = remember { MapPresentationOwnerToken() }
        MapPresentationContent(state, owner, MapViewOptions()) { binding ->
          SideEffect { presentation = binding }
          DisposableEffect(Unit) {
            state.lifecycle.selectAdapterForPresentation(adapter)
            binding.callbacks.onStyleChanged(adapter, style)
            onDispose {}
          }
        }
      }
      waitForIdle()
      runOnIdle {
        assertNull(state.currentMapAttachment)
        assertEquals(listOf("content"), style.layerIds())
        presentation.update(adapter)
      }
      waitForIdle()
      runOnIdle { assertEquals(listOf("content"), style.layerIds()) }
    }
}
