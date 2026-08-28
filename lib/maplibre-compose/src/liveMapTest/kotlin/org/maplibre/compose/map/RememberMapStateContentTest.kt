package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.util.MaplibreComposable

/** [rememberMapState] receives `styleComposition = null` after content: the content clears. */
@OptIn(ExperimentalTestApi::class)
class RememberMapStateContentTest {

  /** The style host owns its dispatcher, so the wait leaves the test thread and polls. */
  private suspend fun awaitStyle(condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
      withTimeout(30.seconds) {
        while (!condition()) delay(10)
      }
    }
  }

  @Test
  fun null_style_content_after_content_clears_it_from_the_style() = runComposeUiTest {
    var content by
      mutableStateOf<(@Composable @MaplibreComposable () -> Unit)?>({
        BackgroundLayer(id = "bg-user")
      })
    lateinit var state: MapState

    setContent { state = rememberMapState(styleComposition = content) }
    waitForIdle()

    val adapter = FakeMapAdapter()
    val binding = RecordingStyleBinding()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    awaitStyle { binding.layerExists("bg-user") }

    content = null
    waitForIdle()
    awaitStyle { !binding.layerExists("bg-user") }
  }

  /** A null baseStyle parameter leaves the style to the state's owner. */
  @Test
  fun an_imperative_base_style_survives_recomposition_when_no_base_style_is_passed() =
    runComposeUiTest {
      var tick by mutableStateOf(0)
      lateinit var state: MapState
      val owned = org.maplibre.compose.style.BaseStyle.Json("""{"version":8}""")

      setContent {
        tick
        state = rememberMapState()
      }
      waitForIdle()

      state.baseStyle = owned
      tick++
      waitForIdle()

      kotlin.test.assertEquals(owned, state.baseStyle, "the remembered state stomped the owner")
    }
}
