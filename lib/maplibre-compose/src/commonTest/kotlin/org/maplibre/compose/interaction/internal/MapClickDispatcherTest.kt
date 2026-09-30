package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
import org.maplibre.compose.interaction.FeatureClickHandler
import org.maplibre.compose.interaction.FeatureInteractions
import org.maplibre.compose.interaction.FeatureInteractionsBuilder
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.map.FeatureQuery
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
              val handler: FeatureClickHandler = {
                delivered += id
                fixture.revision.value = StyleSnapshot.Empty
                ClickResult.Pass
              }
              fixture
                .node(id, handler)
                .copy(
                  interactions =
                    featureInteractions {
                      click(handler)
                      doubleClick(handler)
                      longClick(handler)
                    }
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
    for (mapRow in listOf(false, true)) Fixture().use { fixture ->
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

      if (mapRow) {
        fixture.revision.value = StyleSnapshot.Empty
        fixture.configure(
          MapInteractions(fixture.gestures.value) {
            callbacks {
              features {
                on("front") {
                  click {
                    handled++
                    ClickResult.Consume
                  }
                }
              }
            }
          }
        )
      }
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
          assertEquals(JsonPrimitive("back"), features.single().feature.properties?.get("id"))
          order += "back"
          ClickResult.Pass
        }
      val front =
        fixture
          .node("front") { features ->
            assertEquals(JsonPrimitive("front"), features.single().feature.properties?.get("id"))
            order += "front"
            ClickResult.Pass
          }
          .let {
            it.copy(
              interactions =
                featureInteractions {
                  hitPadding = 5.dp
                  click(it.interactions.onClick)
                }
            )
          }
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
              interactions =
                featureInteractions {
                  click {
                    order += "latest front"
                    ClickResult.Pass
                  }
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
          .let {
            it.copy(
              interactions =
                featureInteractions {
                  hitPadding = 5.dp
                  click(it.interactions.onClick)
                }
            )
          }
      fixture.revision.value = StyleSnapshot(emptyList(), listOf(back, front), emptyList())

      fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event)

      assertEquals(
        listOf(setOf(FeatureQuery("front", 5.dp), FeatureQuery("back", 0.dp))),
        fixture.adapter.layerQueries,
      )
      assertEquals(listOf(fixture.event.screenOffset, fixture.event.screenOffset), offsets)
    }
  }

  @Test
  fun map_rows_pool_base_layers_and_run_before_the_layer_shorthand() = runTest {
    Fixture().use { fixture ->
      val calls = mutableListOf<String>()
      fixture.revision.value =
        StyleSnapshot(
          emptyList(),
          listOf(
            fixture.node("front") {
              calls += "layer"
              ClickResult.Pass
            }
          ),
          emptyList(),
        )
      fixture.configure(
        MapInteractions {
          callbacks {
            features {
              on("back") {
                click {
                  calls += "back"
                  ClickResult.Pass
                }
              }
              on("back", "front", "missing") {
                click { hits ->
                  assertEquals(listOf("front", "back"), hits.map { it.layerId })
                  assertEquals(
                    listOf("front", "back"),
                    hits.map { it.feature.properties?.get("id")?.toString()?.trim('"') },
                  )
                  calls += "group"
                  ClickResult.Pass
                }
              }
              on("front") {
                click {
                  calls += "front"
                  ClickResult.Pass
                }
              }
            }
            click {
              onUnhandled {
                calls += "unhandled"
                ClickResult.Pass
              }
            }
          }
        }
      )
      fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event)
      assertEquals(listOf("group", "front", "layer", "back", "unhandled"), calls)
      assertEquals(
        listOf(setOf(FeatureQuery("front", 0.dp), FeatureQuery("back", 0.dp))),
        fixture.adapter.layerQueries,
      )
    }
  }

  @Test
  fun expanding_search_selects_a_lower_layer_before_a_farther_front_layer() = runTest {
    Fixture().use { fixture ->
      fixture.adapter.featureOffsets["front"] = DpOffset(12.dp, 20.dp)
      val selected = mutableListOf<String>()
      fixture.configure(
        MapInteractions {
          callbacks {
            features {
              for (padding in listOf(0.dp, 8.dp, 16.dp)) {
                on("front", "back") {
                  hitPadding = padding
                  click { hits ->
                    selected += hits.first().layerId
                    ClickResult.Consume
                  }
                }
              }
            }
          }
        }
      )
      assertTrue(fixture.dispatcher.hasHandlers(TapFamily.Tap))
      assertTrue(fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event).consumed)
      assertEquals(listOf("back"), selected)
      assertEquals(
        setOf(0.dp, 8.dp, 16.dp),
        fixture.adapter.layerQueries.single().map { it.hitPadding }.toSet(),
      )
    }
  }

  @Test
  fun rows_keep_their_topmost_layer_priority_when_only_a_lower_layer_has_hits() = runTest {
    Fixture(listOf("back", "middle", "front")).use { fixture ->
      fixture.adapter.featureOffsets["front"] = DpOffset.Zero
      var selected = ""
      fixture.configure(
        MapInteractions {
          callbacks {
            features {
              on("middle") { click { error("the grouped row should consume first") } }
              on("back", "front") {
                click { hits ->
                  assertEquals(listOf("back"), hits.map { it.layerId })
                  selected = "group"
                  ClickResult.Consume
                }
              }
            }
          }
        }
      )
      fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event)
      assertEquals("group", selected)
    }
  }

  @Test
  fun suspended_map_query_updates_callbacks_skips_removed_rows_and_ignores_new_rows() = runTest {
    Fixture().use { fixture ->
      fixture.configure(
        MapInteractions {
          callbacks {
            features {
              on("front") { click { error("old callback") } }
              on("back") { click { error("removed row") } }
            }
          }
        }
      )
      fixture.adapter.gate = CompletableDeferred()
      val delivery = async { fixture.dispatcher.capture(TapFamily.Tap)!!.deliver(fixture.event) }
      fixture.adapter.entered.await()
      var calls = 0
      fixture.configure(
        MapInteractions {
          callbacks {
            features {
              on("missing") { click { error("new row") } }
              on("front") {
                click {
                  calls++
                  ClickResult.Pass
                }
              }
              on("back") {
                hitPadding = 4.dp
                click { error("different query") }
              }
            }
          }
        }
      )
      fixture.adapter.gate!!.complete(Unit)
      delivery.await()
      assertEquals(1, calls)
    }
  }

  @Test
  fun all_click_families_skip_empty_rows_and_can_consume_unhandled() = runTest {
    Fixture().use { fixture ->
      var emptyCalls = 0
      val handler: FeatureClickHandler = {
        emptyCalls++
        ClickResult.Pass
      }
      fixture.configure(
        MapInteractions {
          callbacks {
            features {
              on("missing") {
                click(handler)
                doubleClick(handler)
                longClick(handler)
              }
            }
            click { onUnhandled { ClickResult.Consume } }
            doubleClick { onUnhandled { ClickResult.Consume } }
            longClick { onUnhandled { ClickResult.Consume } }
          }
        }
      )
      for (family in
        listOf(TapFamily.Tap, TapFamily.DoubleTap, TapFamily.LongPress, TapFamily.SecondaryClick)) {
        assertTrue(fixture.dispatcher.hasHandlers(family))
        assertTrue(fixture.dispatcher.capture(family)!!.deliver(fixture.event).consumed)
      }
      assertEquals(0, emptyCalls)
      assertTrue(fixture.adapter.layerQueries.isEmpty())
    }
  }

  @Test
  fun event_rows_unhandled_and_camera_run_in_order_for_every_click_family() = runTest {
    for (family in
      listOf(TapFamily.Tap, TapFamily.DoubleTap, TapFamily.LongPress, TapFamily.SecondaryClick)) {
      Fixture().use { fixture ->
        val calls = mutableListOf<String>()
        val eventHandler: (ClickEvent) -> ClickResult = {
          calls += "event"
          ClickResult.Pass
        }
        val unhandled: (ClickEvent) -> ClickResult = {
          calls += "unhandled"
          ClickResult.Pass
        }
        val rowHandler: FeatureClickHandler = {
          calls += "row"
          ClickResult.Pass
        }
        val layerHandler: FeatureClickHandler = {
          calls += "layer"
          ClickResult.Pass
        }
        fixture.revision.value =
          StyleSnapshot(
            emptyList(),
            listOf(
              fixture
                .node("front", layerHandler)
                .copy(
                  interactions =
                    featureInteractions {
                      click(layerHandler)
                      doubleClick(layerHandler)
                      longClick(layerHandler)
                    }
                )
            ),
            emptyList(),
          )
        fixture.configure(
          MapInteractions {
            callbacks {
              click {
                onEvent(eventHandler)
                onUnhandled(unhandled)
              }
              doubleClick {
                onEvent(eventHandler)
                onUnhandled(unhandled)
              }
              longClick {
                onEvent(eventHandler)
                onUnhandled(unhandled)
              }
              features {
                on("front") {
                  click(rowHandler)
                  doubleClick(rowHandler)
                  longClick(rowHandler)
                }
              }
            }
          }
        )
        val taps =
          TapDispatcher(
            backgroundScope,
            fixture.dispatcher::capture,
            fixture.dispatcher::hasHandlers,
            { InputConfiguration(fixture.gestures.value, InteractionBindings.standard()) },
          )
        val done = CompletableDeferred<Unit>()
        taps.dispatch(family, fixture.sample) {
          calls += "camera"
          done.complete(Unit)
        }
        done.await()
        assertEquals(listOf("event", "row", "layer", "unhandled", "camera"), calls, family.name)
      }
    }
  }

  private class Fixture(layerIds: List<String> = listOf("back", "front")) : AutoCloseable {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Empty)
    val adapter = QueryAdapter()
    val gestures = mutableStateOf(MapInteractions.Standard)

    fun configure(options: MapInteractions) {
      gestures.value = options
    }

    val revision = mutableStateOf<StyleSnapshot?>(null)
    val style = mutableStateOf<StyleBinding?>(RecordingStyleBinding(layers = layerIds.map(::layer)))
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

    fun node(id: String, handler: FeatureClickHandler) =
      StyleSnapshot.Layer(
        layer(id).definition(),
        Anchor.Top,
        featureInteractions { click(handler) },
        registration = Any(),
      )

    override fun close() {
      state.close()
      runtime.close()
    }
  }

  private class QueryAdapter : PresentationTestAdapter() {
    val featureOffsets = mutableMapOf<String, DpOffset>()
    val entered = CompletableDeferred<Unit>()
    var gate: CompletableDeferred<Unit>? = null
    val layerQueries = mutableListOf<Set<FeatureQuery>>()

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
      queries: Set<FeatureQuery>,
    ): Map<FeatureQuery, List<Feature<Geometry, JsonObject?>>> {
      layerQueries += queries
      return super.queryRenderedFeaturesByLayer(offset, queries)
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
    private fun featureInteractions(
      block: FeatureInteractionsBuilder.() -> Unit
    ): FeatureInteractions = FeatureInteractionsBuilder().apply(block).build()

    private fun layer(id: String) =
      TestLayer(id, JsonObject(mapOf("type" to JsonPrimitive("circle"))))
  }
}
