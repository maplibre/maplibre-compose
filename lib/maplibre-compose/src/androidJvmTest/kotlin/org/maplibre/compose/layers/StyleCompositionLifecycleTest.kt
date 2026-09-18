package org.maplibre.compose.layers

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.mlnffi.runPlainComposeUiTest
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.rememberStyleComposition

@OptIn(ExperimentalTestApi::class)
class StyleCompositionLifecycleTest {
  @Test
  fun removed_content_releases_its_resources_and_can_be_composed_again() = runPlainComposeUiTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    var visible by mutableStateOf(true)
    setContent {
      val revision by
        rememberStyleComposition(
          maybeStyle = style,
          content = {
            if (visible) {
              FillLayer(
                id = "toggled",
                source =
                  rememberGeoJsonSource(
                    data = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}""")
                  ),
                color = const(Color.Red),
              )
            }
          },
        )
      LaunchedEffect(revision) { revision?.let { reconciler.apply(style, it) } }
    }
    waitForIdle()
    assertEquals(listOf("toggled"), style.layerIds())
    assertEquals(
      style.sources.keys.single(),
      style.layers.getValue("toggled").getValue("source").jsonPrimitive.content,
    )
    val original = style.layers.getValue("toggled").getValue("paint")

    runOnIdle { visible = false }
    waitForIdle()
    assertTrue(style.layerIds().isEmpty())
    assertTrue(style.sources.isEmpty())

    runOnIdle { visible = true }
    waitForIdle()
    assertEquals(listOf("toggled"), style.layerIds())
    assertEquals(
      style.sources.keys.single(),
      style.layers.getValue("toggled").getValue("source").jsonPrimitive.content,
    )
    assertEquals(original, style.layers.getValue("toggled").getValue("paint"))
  }
}
