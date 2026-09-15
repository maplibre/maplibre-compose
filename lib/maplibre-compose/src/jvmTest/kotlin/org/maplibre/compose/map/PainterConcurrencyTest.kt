package org.maplibre.compose.map

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class PainterConcurrencyTest {
  @Test
  fun concurrent_conversions_do_not_draw_the_same_painter_in_parallel() = runBlocking {
    val active = AtomicInteger()
    val maximum = AtomicInteger()
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(2f, 2f)

        override fun DrawScope.onDraw() {
          maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
          try {
            // Give another worker time to enter the same painter while this draw is active.
            Thread.sleep(100)
            drawRect(Color.Red)
          } finally {
            active.decrementAndGet()
          }
        }
      }
    List(4) {
        async(Dispatchers.Default) {
          ResolvedStyleImage.fromPainter(painter, Density(1f), LayoutDirection.Ltr)
        }
      }
      .awaitAll()
    assertEquals(1, maximum.get())
  }
}
