package org.maplibre.compose.style

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.withRunningRecomposer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.testing.supportsComposeRuntimeTests
import org.maplibre.compose.util.MaplibreComposable

class StyleCompositionEvaluatorTest {

  @Test
  fun one_definition_creates_independent_evaluators() = runTest {
    if (!supportsComposeRuntimeTests) return@runTest
    val started = mutableListOf<Any>()
    val disposed = mutableListOf<Any>()
    val content: @Composable @MaplibreComposable () -> Unit = {
      val evaluatorIdentity = remember { Any() }
      RasterLayer(
        id = "composed-layer",
        source = RasterSource("composed-source", "https://example.invalid/source.json"),
        visible = true,
      )
      DisposableEffect(Unit) {
        started += evaluatorIdentity
        onDispose { disposed += evaluatorIdentity }
      }
    }
    val frameClock = BroadcastFrameClock()
    val firstBinding = RecordingStyleBinding()
    val secondBinding = RecordingStyleBinding()
    var firstRevision: State<DesiredStyleRevision?>? = null
    var secondRevision: State<DesiredStyleRevision?>? = null

    withContext(frameClock) {
      withRunningRecomposer { recomposer ->
        val composition = Composition(UnitApplier(), recomposer)
        try {
          composition.setContent {
            CompositionLocalProvider(
              LocalDensity provides Density(1f),
              LocalLayoutDirection provides LayoutDirection.Ltr,
            ) {
              firstRevision = rememberStyleComposition(content, firstBinding)
              secondRevision = rememberStyleComposition(content, secondBinding)
            }
          }
          while (!frameClock.hasAwaiters) yield()
          frameClock.sendFrame(0)
          yield()
          recomposer.awaitIdle()

          assertEquals(2, started.size)
          assertNotSame(started[0], started[1])
          assertEquals(
            listOf("composed-layer"),
            firstRevision?.value?.layers?.map { it.definition.id },
          )
          assertEquals(
            listOf("composed-layer"),
            secondRevision?.value?.layers?.map { it.definition.id },
          )

          composition.setContent {}
          yield()
          recomposer.awaitIdle()
          assertEquals(started.toSet(), disposed.toSet())
        } finally {
          composition.dispose()
        }
      }
    }
  }

  private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
  }
}
