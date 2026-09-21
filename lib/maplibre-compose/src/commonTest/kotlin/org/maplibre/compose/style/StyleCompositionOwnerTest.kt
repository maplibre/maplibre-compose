package org.maplibre.compose.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.PainterLiteral
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerProperty
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource

@OptIn(ExperimentalCoroutinesApi::class)
class StyleCompositionOwnerTest {
  @Test
  fun shared_images_and_pending_replacements_follow_committed_properties() = runTest {
    val first = painter(Color.Red)
    val equalPixels = painter(Color.Green)
    val replacement = painter(Color.Blue)
    val initialGate = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val starts = mutableListOf<StyleImageRequest>()
    val owner = StyleCompositionOwner { request ->
      starts += request
      if (request == replacement) gate.await() else initialGate.await()
      content(if (request == replacement) 2 else 1)
    }
    val declarations = Channel<StyleDeclaration>(Channel.CONFLATED)
    val revisions = mutableListOf<DesiredStyleRevision>()
    backgroundScope.launch { owner.run(declarations) { revisions += it } }
    runCurrent()
    assertTrue(starts.isEmpty())
    val sprite =
      TestLayer("layer-0", "background").apply {
        paint(
          "background-pattern",
          image("sprite").compile(ExpressionContext.None).asLayerProperty(),
        )
      }
    declarations.send(
      StyleDeclaration(
        emptyList(),
        listOf(DeclaredStyleLayer(DesiredStyleLayer(sprite.definition(), Anchor.Top, null, null))),
      )
    )
    runCurrent()
    val spriteValue = paint(revisions.last())["background-pattern"]
    declarations.send(declaration(listOf(first, first, equalPixels)))
    runCurrent()
    assertEquals(
      spriteValue,
      paint(revisions.last())["background-pattern"],
      "a sprite also stays visible while its replacement is prepared",
    )
    assertEquals(2, starts.size, "one preparation per shared request")
    initialGate.complete(Unit)
    runCurrent()
    assertEquals(1, revisions.last().images.size, "equal pixels share an image")
    val originalSource = revisions.last().sources.single()
    val original = revisions.last().images.single()

    declarations.send(declaration(listOf(replacement), opacity = 0.5f))
    runCurrent()
    val pending = revisions.last()
    assertTrue(pending.imagesPending)
    assertNotEquals(
      originalSource,
      pending.sources.single(),
      "source updates apply while painters wait",
    )
    assertEquals(listOf(original), pending.images)
    assertEquals(JsonPrimitive(original.id), paint(pending)["background-pattern"])
    assertEquals(JsonPrimitive(0.5f), paint(pending)["background-opacity"])
    assertEquals(1, pending.layers.size, "unrelated layer removals apply immediately")

    gate.complete(Unit)
    runCurrent()
    val ready = revisions.last()
    assertFalse(ready.imagesPending)
    assertEquals(2, ready.images.single().image.width)
    assertEquals(JsonPrimitive(ready.images.single().id), paint(ready)["background-pattern"])
    declarations.send(declaration(emptyList()))
    runCurrent()
    assertTrue(revisions.last().images.isEmpty())
  }

  @Test
  fun late_results_cannot_restore_removed_requests_or_publish_after_close() = runTest {
    val request = painter(Color.Red)
    val continuations = mutableListOf<Continuation<StyleImageContent>>()
    val owner = StyleCompositionOwner {
      // Model a native operation which has already started and cannot be cancelled.
      suspendCoroutine { continuations += it }
    }
    val declarations = Channel<StyleDeclaration>(Channel.CONFLATED)
    val revisions = mutableListOf<DesiredStyleRevision>()
    val job = backgroundScope.launch { owner.run(declarations) { revisions += it } }
    declarations.send(declaration(listOf(request)))
    runCurrent()
    declarations.send(declaration(emptyList()))
    runCurrent()
    declarations.send(declaration(listOf(request)))
    runCurrent()
    assertEquals(2, continuations.size)
    continuations[0].resume(content(1))
    runCurrent()
    assertTrue(revisions.last().imagesPending)
    assertTrue(revisions.last().images.isEmpty())
    continuations[1].resume(content(2))
    runCurrent()
    assertEquals(2, revisions.last().images.single().image.width)

    declarations.send(declaration(listOf(painter(Color.Blue))))
    runCurrent()
    job.cancel()
    runCurrent()
    val count = revisions.size
    continuations[2].resume(content(3))
    runCurrent()
    assertEquals(count, revisions.size)
  }

  private fun declaration(requests: List<StyleImageRequest>, opacity: Float = 1f) =
    StyleDeclaration(
      listOf(
        GeoJsonSource(
            "points",
            GeoJsonData.JsonString(
              """{"type":"FeatureCollection","features":[],"value":$opacity}"""
            ),
            GeoJsonOptions(),
          )
          .definition()
      ),
      requests.mapIndexed { index, request ->
        val layer =
          TestLayer("layer-$index", "background").apply {
            paint("background-opacity", const(opacity).asLayerProperty())
          }
        DeclaredStyleLayer(
          DesiredStyleLayer(layer.definition(), Anchor.Top, null, null),
          mapOf(
            StyleProperty("paint", "background-pattern") to
              LayerProperty<ImageValue>(setOf(request)) { JsonPrimitive(it.getValue(request)) }
          ),
        )
      },
    )

  private fun paint(revision: DesiredStyleRevision) =
    revision.layers.first().definition.value["paint"] as JsonObject

  private fun content(width: Int) =
    StyleImageContent(ImageSnapshot.capture(FakeImageBitmap(width, 1)), false, null)

  private fun painter(color: Color) =
    StyleImageRequest.Painter(
      PainterLiteral.of(ColorPainter(color), DpSize(2.dp, 2.dp), false, null),
      graphics,
      Density(1f),
      LayoutDirection.Ltr,
    )

  private val graphics =
    object : GraphicsContext {
      override fun createGraphicsLayer(): GraphicsLayer =
        error("Preparation is controlled by the test")

      override fun releaseGraphicsLayer(layer: GraphicsLayer) = Unit
    }
}
