package org.maplibre.compose.style

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

@OptIn(ExperimentalCoroutinesApi::class)
class StyleCommitTest {
  @Test
  fun a_shared_source_lives_until_its_last_committed_layer_is_removed() = runTest {
    var count by mutableStateOf(2)
    var sourceData by mutableStateOf(data(1))
    Fixture(this).use { fixture ->
      fixture.setContent {
        val source = rememberGeoJsonSource(sourceData)
        repeat(count) { CircleLayer("layer-$it", source, visible = true) }
      }
      val originalSource = fixture.revisions.last().sources.single()
      count = 1
      fixture.frame()
      assertEquals(originalSource, fixture.revisions.last().sources.single())
      sourceData = data(2)
      fixture.frame()
      val updatedSource = fixture.revisions.last().sources.single() as SourceDefinition.GeoJson
      assertEquals(originalSource.id, updatedSource.id)
      assertEquals(data(2), updatedSource.data)
      count = 0
      fixture.frame()
      assertTrue(fixture.revisions.last().sources.isEmpty())
      assertTrue(fixture.revisions.last().layers.isEmpty())
    }
  }

  @Test
  fun failed_evaluation_cannot_change_committed_sources_or_acquire_images() = runTest {
    Fixture(this).use { fixture ->
      fixture.setContent { CircleLayer("points", rememberGeoJsonSource(data(1)), visible = true) }
      val first = fixture.revisions.single()
      var pixelReads = 0
      val bitmap =
        object : androidx.compose.ui.graphics.ImageBitmap by FakeImageBitmap(2, 2) {
          override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
          ) {
            pixelReads++
          }
        }
      assertFailsWith<IllegalStateException> {
        fixture.setContent {
          CircleLayer("points", rememberGeoJsonSource(data(2)), visible = true)
          BackgroundLayer("bitmap", pattern = image(bitmap))
          error("abandon these declarations")
        }
      }
      assertEquals(listOf(first), fixture.revisions)
      assertEquals(0, pixelReads, "abandoned declarations must not capture pixels")
    }
  }

  private class Fixture(private val scope: TestScope) : AutoCloseable {
    val revisions = mutableListOf<StyleSnapshot>()
    val root =
      StyleNode(RecordingStyleBinding(), scope.backgroundScope, publish = { revisions += it })
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.backgroundScope.coroutineContext + clock)
    private val composition = Composition(MapNodeApplier(root), recomposer)

    init {
      scope.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    }

    fun setContent(content: @Composable () -> Unit) {
      composition.setContent {
        CompositionLocalProvider(
          LocalDensity provides Density(1f),
          LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
          StyleContent(root, content)
        }
      }
      scope.runCurrent()
    }

    fun frame() {
      Snapshot.sendApplyNotifications()
      scope.runCurrent()
      clock.sendFrame(0L)
      scope.runCurrent()
    }

    override fun close() {
      root.close()
      composition.dispose()
      recomposer.cancel()
    }
  }

  private fun data(value: Int) =
    GeoJsonData.JsonString("""{"type":"FeatureCollection","features":[],"value":$value}""")
}
