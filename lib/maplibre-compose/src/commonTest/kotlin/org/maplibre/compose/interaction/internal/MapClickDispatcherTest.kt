package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.map.PresentationTestAdapter
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.QueuedOwnerStyleBinding
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleResourceChanges
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.util.VisibleBounds
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class MapClickDispatcherTest {
  @Test
  fun click_families_follow_current_layer_order_and_skip_handlers_removed_by_an_earlier_callback() =
    runTest {
      for (family in
        listOf(TapFamily.Tap, TapFamily.DoubleTap, TapFamily.SecondaryClick, TapFamily.LongPress)) {
        Fixture().use { fixture ->
          val delivered = mutableListOf<String>()
          val nodes =
            listOf("back", "front").map { id ->
              val handler: FeaturesClickHandler = {
                delivered += id
                fixture.revision.value = StyleSnapshot.Empty
                ClickResult.Pass
              }
              fixture
                .node(id, handler)
                .copy(
                  onDoubleClick = handler,
                  onLongClick = handler,
                )
            }
          fixture.revision.value = StyleSnapshot(emptyList(), nodes, emptyList())
          val path = checkNotNull(fixture.dispatcher.capture(family))
          checkNotNull(fixture.style.value).moveLayer("back", "")
          fixture.publishLayerOrder()
          path.deliver(ClickEvent(fixture.sample))
          assertEquals(listOf("back"), delivered, family.name)
        }
      }
    }

  @Test
  fun a_tap_before_layer_handles_are_published_waits_for_them() = runTest {
    Fixture().use { fixture ->
      // The owner has loaded the style, but its read that publishes the handles has not run.
      val binding = QueuedOwnerStyleBinding(checkNotNull(fixture.style.value))
      fixture.style.value = binding
      binding.ownerBusy = true
      assertTrue(fixture.state.styleAuthority.updateLoadedStyle(fixture.adapter, binding))
      val ready =
        launch(start = CoroutineStart.UNDISPATCHED) {
          fixture.state.styleAuthority.markStyleReady(fixture.adapter)
        }
      var handled = 0
      var unhandled = 0
      fixture.revision.value =
        StyleSnapshot(
          emptyList(),
          listOf(
            fixture.node("front") {
              handled++
              ClickResult.Consume
            }
          ),
          emptyList(),
        )
      fixture.configure(
        MapInteractions {
          callbacks {
            click {
              onUnhandled {
                unhandled++
                ClickResult.Pass
              }
            }
          }
        }
      )

      val path = checkNotNull(fixture.dispatcher.capture(TapFamily.Tap))
      val delivery = async(start = CoroutineStart.UNDISPATCHED) { path.deliver(fixture.event) }
      assertFalse(delivery.isCompleted, "the tap waits for the handles")
      assertEquals(0, unhandled)
      binding.runOwnerTasks()
      ready.join()

      assertTrue(delivery.await().consumed)
      assertEquals(1, handled)
      assertEquals(0, unhandled)
    }
  }

  @Test
  fun a_waiting_tap_is_dropped_when_the_presentation_detaches() = runTest {
    Fixture().use { fixture ->
      val binding = QueuedOwnerStyleBinding(checkNotNull(fixture.style.value))
      fixture.style.value = binding
      binding.ownerBusy = true
      assertTrue(fixture.state.styleAuthority.updateLoadedStyle(fixture.adapter, binding))
      val ready =
        launch(start = CoroutineStart.UNDISPATCHED) {
          fixture.state.styleAuthority.markStyleReady(fixture.adapter)
        }
      var handled = 0
      fixture.revision.value =
        StyleSnapshot(
          emptyList(),
          listOf(
            fixture.node("front") {
              handled++
              ClickResult.Consume
            }
          ),
          emptyList(),
        )
      val path = checkNotNull(fixture.dispatcher.capture(TapFamily.Tap))
      val delivery = async(start = CoroutineStart.UNDISPATCHED) { path.deliver(fixture.event) }
      assertFalse(delivery.isCompleted, "the tap waits for the handles")

      // The presentation detaches before its style finishes loading.
      fixture.state.releasePresentation(fixture.token, fixture.adapter)

      assertFailsWith<CancellationException> { delivery.await() }
      assertFalse(path.isValid())
      assertEquals(0, handled)
      binding.runOwnerTasks()
      ready.join()
    }
  }

  @Test
  fun layers_and_unhandled_follow_loaded_order_and_padding() = runTest {
    Fixture().use { fixture ->
      val order = mutableListOf<String>()
      fixture.adapter.featureOffsets["front"] = DpOffset(12.dp, 20.dp)
      val back =
        fixture.node("back") { features ->
          assertEquals(JsonPrimitive("back"), features.single().properties?.get("id"))
          order += "back"
          ClickResult.Pass
        }
      val front =
        fixture
          .node("front") { features ->
            assertEquals(JsonPrimitive("front"), features.single().properties?.get("id"))
            order += "front"
            ClickResult.Pass
          }
          .copy(hitPadding = 5.dp)
      fixture.revision.value = StyleSnapshot(emptyList(), listOf(front, back), emptyList())
      fixture.configure(
        MapInteractions {
          callbacks {
            click {
              onUnhandled {
                order += "unhandled"
                ClickResult.Pass
              }
            }
          }
        }
      )
      assertEquals(
        ClickResult.Pass,
        fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event),
      )
      assertEquals(listOf("front", "back", "unhandled"), order)
    }
  }

  @Test
  fun suspended_query_uses_latest_surviving_handler_and_skips_replaced_registration() = runTest {
    Fixture().use { fixture ->
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
      fixture.revision.value = StyleSnapshot(emptyList(), listOf(back, front), emptyList())
      fixture.adapter.gate = CompletableDeferred()
      val path = fixture.dispatcher.capture(TapFamily.Tap)!!
      val delivery = async { path.deliver(fixture.event) }
      fixture.adapter.entered.await()
      fixture.revision.value =
        StyleSnapshot(
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
    }
  }

  @Test
  fun style_invalidation_during_a_query_stops_handlers_and_fallthrough() = runTest {
    Fixture().use { fixture ->
      var calls = 0
      fixture.revision.value =
        StyleSnapshot(
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
    }
  }

  @Test
  fun unhandled_click_can_consume_without_any_layer_subscribers() = runTest {
    Fixture().use { fixture ->
      fixture.dispatcher.capture(TapFamily.DoubleTap)!!.deliver(fixture.event)
      var unhandled = 0
      fixture.configure(
        MapInteractions {
          callbacks {
            click {
              onUnhandled {
                unhandled++
                ClickResult.Consume
              }
            }
          }
        }
      )
      assertTrue(fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event).consumed)
      assertEquals(1, unhandled)
    }
  }

  @Test
  fun unhandled_callback_reads_replacement_body_after_layer_removes_itself() = runTest {
    Fixture().use { fixture ->
      val calls = mutableListOf<String>()
      fixture.configure(
        MapInteractions {
          callbacks { click { onUnhandled { error("old unhandled") } } }
        }
      )
      fixture.revision.value =
        StyleSnapshot(
          emptyList(),
          listOf(
            fixture.node("front") {
              calls += "layer"
              fixture.revision.value = StyleSnapshot(emptyList(), emptyList(), emptyList())
              fixture.configure(
                MapInteractions {
                  callbacks {
                    click {
                      onUnhandled {
                        calls += "current unhandled"
                        ClickResult.Pass
                      }
                    }
                  }
                }
              )
              ClickResult.Pass
            }
          ),
          emptyList(),
        )
      val path = checkNotNull(fixture.dispatcher.capture(TapFamily.Tap))
      assertEquals(ClickResult.Pass, path.deliver(fixture.event))
      assertEquals(listOf("layer", "current unhandled"), calls)
    }
  }

  @Test
  fun one_query_covers_every_layer_and_handlers_read_the_click() = runTest {
    Fixture().use { fixture ->
      val offsets = mutableListOf<DpOffset>()
      val back =
        fixture.node("back") {
          offsets += screenOffset
          ClickResult.Pass
        }
      val front =
        fixture
          .node("front") {
            offsets += screenOffset
            ClickResult.Pass
          }
          .copy(hitPadding = 5.dp)
      fixture.revision.value = StyleSnapshot(emptyList(), listOf(back, front), emptyList())

      fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event)

      assertEquals(
        listOf(mapOf("front" to 5.dp, "back" to 0.dp)),
        fixture.adapter.layerQueries,
      )
      assertEquals(listOf(fixture.event.screenOffset, fixture.event.screenOffset), offsets)
    }
  }

  private class Fixture : AutoCloseable {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val adapter = QueryAdapter()
    val gestures = mutableStateOf(MapInteractions.Standard)

    fun configure(options: MapInteractions) {
      gestures.value = options
    }

    val revision = mutableStateOf<StyleSnapshot?>(null)
    val style =
      mutableStateOf<StyleBinding?>(
        RecordingStyleBinding(layers = listOf(layer("back"), layer("front")))
      )
    val dispatcher: FeatureClickDispatcher
    val sample =
      GesturePointerSample(
        10,
        DpOffset(10.dp, 20.dp),
        Position(0.0, 0.0),
        emptySet(),
        emptySet(),
        emptySet(),
      )
    val event = ClickEvent(sample)

    val token = state.reservePresentation()

    init {
      state.publishPresentation(token, adapter)
      // The dispatcher orders handlers by the layer handles the map state publishes.
      state.durableStyleCallbacks().onStyleChanged(adapter, checkNotNull(style.value))
      state.durableStyleCallbacks().onStyleReady(adapter)
      dispatcher =
        FeatureClickDispatcher(
          state,
          mutableStateOf<State<StyleSnapshot?>>(revision),
          style,
          gestures,
        )
    }

    /** Publishes the engine's current layer order the way a reconciled revision does. */
    suspend fun publishLayerOrder() {
      val binding = checkNotNull(style.value)
      state.styleAuthority.updateStyleResources(
        adapter,
        StyleResourceChanges(binding.identity).apply { layerOrder = binding.layerIds() },
      )
    }

    fun node(id: String, handler: FeaturesClickHandler) =
      StyleSnapshot.Layer(layer(id).definition(), Anchor.Top, handler, null, registration = Any())

    override fun close() {
      state.close()
      runtime.close()
    }
  }

  private class QueryAdapter : PresentationTestAdapter() {
    val featureOffsets = mutableMapOf<String, DpOffset>()
    val entered = CompletableDeferred<Unit>()
    var gate: CompletableDeferred<Unit>? = null
    val layerQueries = mutableListOf<Map<String, Dp>>()

    init {
      currentViewport =
        Viewport(
          CameraPosition(),
          DpSize(100.dp, 100.dp),
          VisibleBounds(Position(-1.0, -1.0), Position(1.0, 1.0)),
          VisibleRegion(
            Position(-1.0, 1.0),
            Position(1.0, 1.0),
            Position(-1.0, -1.0),
            Position(1.0, -1.0),
          ),
          1.0,
        )
    }

    override suspend fun queryRenderedFeaturesByLayer(
      offset: DpOffset,
      hitPadding: Map<String, Dp>,
    ): Map<String, List<Feature<Geometry, JsonObject?>>> {
      layerQueries += hitPadding
      return super.queryRenderedFeaturesByLayer(offset, hitPadding)
    }

    override suspend fun queryRenderedFeatures(
      offset: DpOffset,
      layerIds: Set<String>?,
      predicate: CompiledExpression<BooleanValue>?,
    ): List<Feature<Geometry, JsonObject?>> =
      query(layerIds, DpRect(offset.x, offset.y, offset.x, offset.y))

    override suspend fun queryRenderedFeatures(
      rect: DpRect,
      layerIds: Set<String>?,
      predicate: CompiledExpression<BooleanValue>?,
    ): List<Feature<Geometry, JsonObject?>> = query(layerIds, rect)

    private suspend fun query(
      ids: Set<String>?,
      rect: DpRect,
    ): List<Feature<Geometry, JsonObject?>> {
      entered.complete(Unit)
      gate?.await()
      return ids.orEmpty().mapNotNull { id ->
        val point = featureOffsets[id] ?: DpOffset(10.dp, 20.dp)
        if (point.x !in rect.left..rect.right || point.y !in rect.top..rect.bottom) null
        else Feature(Point(Position(0.0, 0.0)), JsonObject(mapOf("id" to JsonPrimitive(id))))
      }
    }
  }

  companion object {
    private fun layer(id: String) =
      TestLayer(id, JsonObject(mapOf("type" to JsonPrimitive("circle"))))
  }
}
