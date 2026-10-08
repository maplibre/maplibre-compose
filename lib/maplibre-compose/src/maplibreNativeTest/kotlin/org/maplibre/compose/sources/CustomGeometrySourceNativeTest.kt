package org.maplibre.compose.sources

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.map.readMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.MlnFfiMapFixture
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class CustomGeometrySourceNativeTest {

  @Test
  fun provider_failure_completes_as_an_empty_tile(): MapTestResult = runMapTest {
    val requested = CompletableDeferred<Unit>()
    val fail = CompletableDeferred<Unit>()
    val fixture = createMapFixture() as MlnFfiMapFixture
    fixture.use {
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        CustomGeometrySource("custom-geometry") {
          requested.complete(Unit)
          fail.await()
          error("fixture provider failure")
        }
      style.install(source)
      style.install(TestLayer("custom-fill", "fill", source))
      fun isMapFullyLoaded(): Boolean? = fixture.bridge.session.readMap { map -> map.isFullyLoaded }

      fixture.pumpUntil("the source to request a tile") { requested.isCompleted }
      assertFalse(
        isMapFullyLoaded() ?: true,
        "the pending provider must keep the map from finishing its load",
      )
      fail.complete(Unit)
      fixture.pumpUntil("the failed tile request to complete", 5.seconds) {
        isMapFullyLoaded() == true
      }

      assertTrue(fixture.state.queryRenderedFeatures(offset = DpOffset(256.dp, 256.dp)).isEmpty())
    }
  }
}
