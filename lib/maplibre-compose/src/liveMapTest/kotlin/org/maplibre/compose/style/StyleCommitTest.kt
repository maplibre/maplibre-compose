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
import org.maplibre.compose.layers.BitmapKey
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource

@OptIn(ExperimentalCoroutinesApi::class)
class StyleCommitTest {
  @Test
  fun a_shared_source_lives_until_its_last_committed_layer_is_removed() = runTest {
    var count by mutableStateOf(2)
    Fixture(this).use { fixture ->
      fixture.setContent {
        val source = rememberGeoJsonSource(data(1))
        repeat(count) { CircleLayer("layer-$it", source, visible = true) }
      }
      assertEquals(1, fixture.revisions.last().sources.size)
      count = 1
      fixture.frame()
      assertEquals(1, fixture.revisions.last().sources.size)
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
      val bitmap = FakeImageBitmap(2, 2)
      assertFailsWith<IllegalStateException> {
        fixture.setContent {
          CircleLayer("points", rememberGeoJsonSource(data(2)), visible = true)
          BackgroundLayer("bitmap", pattern = image(bitmap))
          error("abandon these declarations")
        }
      }
      assertEquals(listOf(first), fixture.revisions)
      assertEquals(first, fixture.root.snapshotRevision())
      var recaptured = false
      fixture.root.images.bitmap(BitmapKey(bitmap, false, null)) {
        recaptured = true
        StyleImageCache.Content(ImageSnapshot.capture(bitmap), false, null)
      }
      assertTrue(recaptured, "an abandoned evaluation must not leave a cached bitmap request")
    }
  }

  @Test
  fun disposing_composition_does_not_clear_installed_content() = runTest {
    val fixture = Fixture(this)
    try {
      fixture.setContent { BackgroundLayer("bitmap", pattern = image(FakeImageBitmap(2, 2))) }
      val committed = fixture.revisions.last()
      assertEquals(1, committed.images.size)
      assertEquals(1, committed.layers.size)
      fixture.close()
      assertEquals(committed, fixture.revisions.last())
    } finally {
      fixture.close()
    }
  }

  private class Fixture(private val scope: TestScope) : AutoCloseable {
    val revisions = mutableListOf<DesiredStyleRevision>()
    val root = StyleNode(RecordingStyleBinding(), publish = { revisions += it })
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
