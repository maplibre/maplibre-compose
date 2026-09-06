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
  fun click_families_follow_current_layer_order_and_skip_handlers_removed_by_an_earlier_callback() =
    runTest {
      for (family in TapFamily.entries) {
        Fixture().use { fixture ->
          val delivered = mutableListOf<String>()
          val nodes =
            listOf("back", "front").map { id ->
              val handler: FeaturesClickHandler = {
                delivered += id
                fixture.revision.value = DesiredStyleRevision.Empty
                ClickResult.Pass
              }
              fixture
                .node(id, handler)
                .copy(
                  onDoubleClick = handler,
                  onContextClick = handler,
                  onTwoFingerClick = handler,
                )
            }
          fixture.revision.value = DesiredStyleRevision(emptyList(), nodes, emptyList())
          val path = checkNotNull(fixture.dispatcher.capture(family))
          checkNotNull(fixture.style.value).moveLayer("back", "")
          path.deliver(family.event(fixture.sample))
          assertEquals(listOf("back"), delivered, family.name)
        }
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
      fixture.revision.value = DesiredStyleRevision(emptyList(), listOf(front, back), emptyList())
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
    }
  }

  @Test
  fun style_invalidation_during_a_query_stops_handlers_and_fallthrough() = runTest {
    Fixture().use { fixture ->
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
  fun newly_added_layer_and_unhandled_slots_do_not_join_an_admitted_click() = runTest {
    Fixture().use { fixture ->
      val calls = mutableListOf<String>()
      val path = checkNotNull(fixture.dispatcher.capture(TapFamily.Tap))
      fixture.revision.value =
        DesiredStyleRevision(
          emptyList(),
          listOf(
            fixture.node("front") {
              calls += "new layer"
              ClickResult.Pass
            }
          ),
          emptyList(),
        )
      fixture.configure(
        MapInteractions {
          callbacks {
            click {
              onUnhandled {
                calls += "new unhandled"
                ClickResult.Pass
              }
            }
          }
        }
      )
      assertEquals(ClickResult.Pass, path.deliver(fixture.event))
      assertTrue(calls.isEmpty())
    }
  }

  @Test
  fun admitted_unhandled_slot_reads_replacement_body_after_layer_removes_itself() = runTest {
    Fixture().use { fixture ->
      val calls = mutableListOf<String>()
      fixture.configure(
        MapInteractions {
          callbacks { click { onUnhandled { error("old unhandled") } } }
        }
      )
      fixture.revision.value =
        DesiredStyleRevision(
          emptyList(),
          listOf(
            fixture.node("front") {
              calls += "layer"
              fixture.revision.value = DesiredStyleRevision(emptyList(), emptyList(), emptyList())
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
  fun layer_and_unhandled_resubscriptions_cannot_join_a_suspended_click_query() = runTest {
    Fixture().use { fixture ->
      var calls = 0
      val registered = org.maplibre.compose.style.LayerNode(layer("front"), Anchor.Top)
      registered.onClick = {
        calls++
        ClickResult.Pass
      }
      fun revision() =
        DesiredStyleRevision(
          emptyList(),
          listOf(
            DesiredStyleLayer(
              registered.layer.definition(),
              Anchor.Top,
              registered.onClick,
              null,
              registration = registered,
              clickSubscription = registered.clickSubscription.capture(),
            )
          ),
          emptyList(),
        )
      fixture.revision.value = revision()
      fixture.configure(
        MapInteractions {
          callbacks {
            click {
              onUnhandled {
                calls++
                ClickResult.Pass
              }
            }
          }
        }
      )
      val path = checkNotNull(fixture.dispatcher.capture(TapFamily.Tap))
      fixture.adapter.gate = CompletableDeferred()
      val delivery = async { path.deliver(fixture.event) }
      fixture.adapter.entered.await()
      registered.onClick = null
      fixture.revision.value = revision()
      registered.onClick = {
        calls++
        ClickResult.Pass
      }
      fixture.revision.value = revision()
      fixture.configure(MapInteractions.Standard)
      fixture.configure(
        MapInteractions {
          callbacks {
            click {
              onUnhandled {
                calls++
                ClickResult.Pass
              }
            }
          }
        }
      )
      fixture.adapter.gate!!.complete(Unit)
      assertEquals(ClickResult.Pass, delivery.await())
      assertEquals(0, calls)
    }
  }

  private class Fixture : AutoCloseable {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val adapter = QueryAdapter()
    val gestures = mutableStateOf(MapInteractions.Standard)
    val subscriptions = InteractionSubscriptions(MapInteractions.Standard)

    fun configure(options: MapInteractions) {
      gestures.value = options
      subscriptions.update(options)
    }

    val revision = mutableStateOf<DesiredStyleRevision?>(null)
    val style =
      mutableStateOf<StyleBinding?>(
        RecordingStyleBinding(layers = listOf(layer("back"), layer("front")))
      )
    val dispatcher: MapInteractionDispatcher
    val sample =
      GesturePointerSample(
        1,
        10,
        DpOffset(10.dp, 20.dp),
        Position(0.0, 0.0),
        emptySet(),
        emptySet(),
        emptySet(),
      )
    val event = TapEvent(sample)

    init {
      state.publishPresentation(state.reservePresentation(), adapter)
      dispatcher =
        MapInteractionDispatcher(
          state,
          mutableStateOf<State<DesiredStyleRevision?>>(revision),
          style,
          gestures,
          subscriptions,
        )
    }

    fun node(id: String, handler: FeaturesClickHandler) =
      DesiredStyleLayer(layer(id).definition(), Anchor.Top, handler, null, registration = Any())

    override fun close() {
      state.close()
      runtime.close()
    }
  }

  private class QueryAdapter : PresentationTestAdapter() {
    val featureOffsets = mutableMapOf<String, DpOffset>()
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
      UnknownLayer(id, JsonObject(mapOf("type" to JsonPrimitive("circle"))))
  }
}
