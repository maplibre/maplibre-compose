package org.maplibre.compose.map

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleSnapshot

@OptIn(ExperimentalTestApi::class)
class AnchorPredicateExceptionTest {
  /** The exception escapes the map's composition uncaught, as one thrown by a composable would. */
  @Test
  fun a_throwing_predicate_fails_the_composition_that_holds_the_map() {
    val bug = IllegalStateException("bad predicate")
    val style = RecordingStyleBinding(layers = listOf(TestLayer("base", "background")))
    var state: MapState? = null
    val thrown =
      assertFailsWith<IllegalStateException> {
        runPlainComposeUiTest {
          val reconciler = StyleReconciler()
          val adapter =
            object : PresentationTestAdapter() {
              override suspend fun <T> reconcileStyleRevision(
                revision: StyleSnapshot,
                capture: (StyleBinding) -> T,
              ): T {
                reconciler.apply(style, revision)
                return capture(style)
              }
            }
          setContent {
            val runtime = remember { mapRuntimeForTest() }
            DisposableEffect(runtime) { onDispose { runtime.close() } }
            val current = remember {
              runtime.createMapState(BaseStyle.Empty) {
                Anchor.Above({ throw bug }) { BackgroundLayer("over", visible = true) }
              }
            }
            state = current
            MapPresentationContent(
              current,
              remember { MapPresentationOwnerToken() },
              MapViewOptions(),
            ) { binding ->
              DisposableEffect(Unit) {
                current.lifecycle.selectAdapterForPresentation(adapter)
                binding.callbacks.onStyleChanged(adapter, style)
                onDispose {}
              }
            }
          }
          waitForIdle()
        }
      }

    // Coroutines on the JVM can rethrow a copy of the exception that carries its stack trace.
    assertEquals(bug.message, thrown.message)
    assertEquals(listOf("base"), style.layerIds())
    assertIsNot<StyleLoadState.Failed>(assertNotNull(state).style.loadState)
  }
}
