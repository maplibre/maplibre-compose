package org.maplibre.compose.layers

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
  fun source_submission_needs_no_frame_after_the_committing_frame() = runPlainComposeUiTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    val initial = GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[]}""")
    val updated =
      GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[],"updated":true}""")
    var data by mutableStateOf(initial)
    setContent {
      rememberStyleComposition(
        maybeStyle = style,
        content = { FillLayer("points", rememberGeoJsonSource(data), visible = true) },
        applyRevision = { binding, revision -> reconciler.apply(binding, revision) },
      )
    }
    waitForIdle()
    mainClock.autoAdvance = false
    try {
      runOnIdle { data = updated }
      mainClock.advanceTimeByFrame()
      waitUntil(timeoutMillis = 5_000) { style.installedGeoJson.values.any { updated in it } }
      assertEquals<List<GeoJsonData>>(listOf(updated), style.installedGeoJson.values.single())
    } finally {
      mainClock.autoAdvance = true
    }
  }

  @Test
  fun removed_content_releases_its_resources_and_can_be_composed_again() = runPlainComposeUiTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    var visible by mutableStateOf(true)
    setContent {
      rememberStyleComposition(
        maybeStyle = style,
        applyRevision = { binding, revision -> reconciler.apply(binding, revision) },
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
