package org.maplibre.compose.map

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalTestApi::class)
class RememberMapStyleTest {
  @Test
  fun recomposition_updates_the_owned_base_style_without_replacing_the_map() = runComposeUiTest {
    val runtime = mapRuntimeForTest()
    val seed = mutableStateOf<BaseStyle>(BaseStyle.Empty)
    lateinit var state: MapState
    setContent {
      val remembered = rememberMapState(runtime, baseStyle = seed.value)
      SideEffect { state = remembered }
    }
    waitForIdle()
    val original = state
    val replacement = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
    runOnIdle {
      assertNull(state.style.asMutable)
      seed.value = replacement
    }
    runOnIdle {
      assertSame(original, state)
      assertEquals(replacement, state.style.baseStyle)
    }
    runtime.close()
    runtime.awaitClosed()
  }
}
