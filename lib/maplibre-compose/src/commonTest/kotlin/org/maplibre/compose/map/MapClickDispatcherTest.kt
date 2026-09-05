package org.maplibre.compose.map

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.UnknownLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class MapClickDispatcherTest {
  @Test
  fun hover_uses_loaded_order_and_exact_points_even_with_tap_padding() = runTest {
    val fixture = Fixture()
    try {
      val back = fixture.node("back") { ClickResult.Pass }.copy(onHover = {})
      val front = fixture.node("front") { ClickResult.Pass }.copy(hitPadding = 25.dp, onHover = {})
      fixture.revision.value = DesiredStyleRevision(emptyList(), listOf(front, back), emptyList())
      val scene = checkNotNull(fixture.dispatcher.captureHover())
      assertEquals(listOf("front", "back"), scene.layers.map { it.id })
      scene.layers.forEach { assertTrue(scene.query(it, fixture.event.screenOffset)) }
      assertEquals(listOf<DpRect?>(null, null), fixture.adapter.rectangles)
      val revision = fixture.dispatcher.hoverRevision
      fixture.dispatcher.presentationChanged(fixture.adapter)
      assertTrue(revision != fixture.dispatcher.hoverRevision)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun map_layers_and_unhandled_follow_loaded_order_and_padding() = runTest {
    val fixture = Fixture()
    try {
      val order = mutableListOf<String>()
      val back =
        fixture.node("back") {
          order += "back"
          ClickResult.Pass
        }
      val front =
        fixture
          .node("front") {
            order += "front"
            ClickResult.Pass
          }
          .copy(hitPadding = 5.dp)
      fixture.revision.value = DesiredStyleRevision(emptyList(), listOf(front, back), emptyList())
      fixture.handlers.value =
        fixture.handlers.value.copy(
          onClick = { _, _ ->
            order += "map"
            ClickResult.Pass
          },
          onUnhandledClick = { _, _ ->
            order += "unhandled"
            ClickResult.Pass
          },
        )
      assertEquals(
        ClickResult.Pass,
        fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event),
      )
      assertEquals(listOf("map", "front", "back", "unhandled"), order)
      assertEquals(listOf("front", "back"), fixture.adapter.queries)
      assertEquals(listOf(DpRect(5.dp, 15.dp, 15.dp, 25.dp), null), fixture.adapter.rectangles)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun suspended_query_uses_latest_surviving_handler_and_skips_replaced_registration() = runTest {
    val fixture = Fixture()
    try {
      val order = mutableListOf<String>()
      val back =
        fixture.node("back") {
          order += "old back"
          ClickResult.Pass
        }
      val front =
        fixture.node("front") {
          order += "old front"
          ClickResult.Pass
        }
      fixture.revision.value = DesiredStyleRevision(emptyList(), listOf(back, front), emptyList())
      fixture.adapter.gate = CompletableDeferred()
      val path = fixture.dispatcher.capture(TapFamily.Tap)!!
      val delivery = async { path.deliver(fixture.event) }
      fixture.adapter.entered.await()
      fixture.revision.value =
        DesiredStyleRevision(
          emptyList(),
          listOf(
            fixture.node("back") {
              order += "new back"
              ClickResult.Pass
            },
            front.copy(
              onClick = {
                order += "latest front"
                ClickResult.Pass
              }
            ),
          ),
          emptyList(),
        )
      fixture.adapter.gate!!.complete(Unit)
      delivery.await()
      assertEquals(listOf("latest front"), order)
      assertEquals(listOf("front"), fixture.adapter.queries)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun style_invalidation_during_a_query_stops_handlers_and_fallthrough() = runTest {
    val fixture = Fixture()
    try {
      var calls = 0
      fixture.revision.value =
        DesiredStyleRevision(
          emptyList(),
          listOf(
            fixture.node("front") {
              calls++
              ClickResult.Pass
            }
          ),
          emptyList(),
        )
      fixture.adapter.gate = CompletableDeferred()
      val path = fixture.dispatcher.capture(TapFamily.Tap)!!
      val delivery = async { path.deliver(fixture.event) }
      fixture.adapter.entered.await()
      fixture.style.value = RecordingStyleBinding()
      fixture.adapter.gate!!.complete(Unit)
      assertTrue(delivery.await().consumed)
      assertTrue(!path.isValid())
      assertEquals(0, calls)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun no_layer_subscribers_means_no_query_and_map_consumption_skips_layers() = runTest {
    val fixture = Fixture()
    try {
      assertEquals(emptySet(), fixture.dispatcher.capabilities)
      fixture.dispatcher.capture(TapFamily.DoubleTap)!!.deliver(fixture.event)
      assertTrue(fixture.adapter.queries.isEmpty())
      fixture.handlers.value =
        fixture.handlers.value.copy(onDoubleClick = { _, _ -> ClickResult.Consume })
      val node =
        fixture
          .node("front") { ClickResult.Pass }
          .copy(onDoubleClick = { error("consumed click reached layer") })
      fixture.revision.value = DesiredStyleRevision(emptyList(), listOf(node), emptyList())
      assertTrue(TapFamily.DoubleTap in fixture.dispatcher.capabilities)
      assertTrue(fixture.dispatcher.capture(TapFamily.DoubleTap)!!.deliver(fixture.event).consumed)
      assertTrue(fixture.adapter.queries.isEmpty())
    } finally {
      fixture.close()
    }
  }

  private class Fixture {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val adapter = QueryAdapter()
    val handlers = mutableStateOf(MapClickHandlers(null, null, null, null, null))
    val revision = mutableStateOf<DesiredStyleRevision?>(null)
    val style =
      mutableStateOf<StyleBinding?>(
        RecordingStyleBinding(layers = listOf(layer("back"), layer("front")))
      )
    val dispatcher: MapInteractionDispatcher
    val event =
      TapEvent(
        GesturePointerSample(
          1,
          10,
          DpOffset(10.dp, 20.dp),
          Position(0.0, 0.0),
          emptySet(),
          emptySet(),
          emptySet(),
        )
      )

    init {
      state.publishPresentation(state.reservePresentation(), adapter)
      dispatcher =
        MapInteractionDispatcher(
          state,
          handlers,
          mutableStateOf<State<DesiredStyleRevision?>>(revision),
          style,
          mutableStateOf(MapGestures.Standard),
        )
    }

    fun node(id: String, handler: FeaturesClickHandler) =
      DesiredStyleLayer(layer(id).definition(), Anchor.Top, handler, null, registration = Any())

    fun close() {
      state.close()
      runtime.close()
    }
  }

  private class QueryAdapter : PresentationTestAdapter() {
    val queries = mutableListOf<String>()
    val rectangles = mutableListOf<DpRect?>()
    val entered = CompletableDeferred<Unit>()
    var gate: CompletableDeferred<Unit>? = null

    init {
      currentViewport =
        Viewport(
          DpSize(100.dp, 100.dp),
          BoundingBox(Position(-1.0, -1.0), Position(1.0, 1.0)),
          VisibleRegion(
            Position(-1.0, 1.0),
            Position(1.0, 1.0),
            Position(-1.0, -1.0),
            Position(1.0, -1.0),
          ),
          1.0,
        )
    }

    override suspend fun queryRenderedFeatures(
      offset: DpOffset,
      layerIds: Set<String>?,
      predicate: CompiledExpression<BooleanValue>?,
    ): List<Feature<Geometry, JsonObject?>> = query(layerIds, null)

    override suspend fun queryRenderedFeatures(
      rect: DpRect,
      layerIds: Set<String>?,
      predicate: CompiledExpression<BooleanValue>?,
    ): List<Feature<Geometry, JsonObject?>> = query(layerIds, rect)

    private suspend fun query(
      ids: Set<String>?,
      rect: DpRect?,
    ): List<Feature<Geometry, JsonObject?>> {
      queries += ids!!.single()
      rectangles += rect
      entered.complete(Unit)
      gate?.await()
      return listOf(Feature(Point(Position(0.0, 0.0)), JsonObject(emptyMap())))
    }
  }

  companion object {
    private fun layer(id: String) =
      UnknownLayer(id, JsonObject(mapOf("type" to JsonPrimitive("circle"))))
  }
}
