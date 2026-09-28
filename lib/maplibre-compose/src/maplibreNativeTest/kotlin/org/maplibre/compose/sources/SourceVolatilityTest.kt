package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class SourceVolatilityTest {
  @Test
  fun queued_writes_capture_values_without_blocking_or_changing_a_replacement() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val source =
        VectorTileSource(
          "tiles",
          listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
          TileSetOptions(),
        )
      binding.addSource(source.definition())
      val featureHandle = binding.handle(source) as VectorTileSourceHandle
      val handle = assertNotNull(featureHandle.asMutable)
      val nested = mutableMapOf("value" to JsonPrimitive("submitted"))
      val state = mutableMapOf("selected" to JsonPrimitive(true), "nested" to JsonObject(nested))
      val parked = TestLatch(1)
      val release = TestLatch(1)
      val finished = CompletableDeferred<Boolean>()
      val replacement = CompletableDeferred<Result<Unit>>()
      try {
        assertTrue(
          fixture.session.postOwnerTaskForTest {
            parked.countDown()
            finished.complete(release.await(5_000L))
          }
        )
        assertTrue(parked.await(5_000L), "native owner did not reach the gate")
        handle.setVolatile(true)
        featureHandle.setFeatureState("layer", "1", JsonObject(state))
        state["selected"] = JsonPrimitive(false)
        nested["value"] = JsonPrimitive("changed after submission")
        assertTrue(
          fixture.session.postOwnerTaskForTest {
            replacement.complete(
              runCatching {
                assertEquals(
                  true,
                  binding.readMap { it.styleSourceInfo(source.id)?.volatileSource },
                )
                assertEquals(
                  Json.parseToJsonElement("""{"selected":true,"nested":{"value":"submitted"}}"""),
                  binding.readMap {
                    Json.parseToJsonElement(
                      it
                        .getFeatureState(featureStateSelector(source.id, "layer", "1"))
                        .decodeToString()
                    )
                  },
                )
                binding.removeSource(source.id)
                binding.addSource(source.definition())
                Unit
              }
            )
          }
        )
        // This command belongs to the old installation until queued removal actually commits.
        handle.setVolatile(true)
        assertEquals(false, finished.isCompleted, "source write waited for the native owner")
      } finally {
        release.countDown()
      }
      assertTrue(finished.await(), "native owner gate timed out")
      replacement.await().getOrThrow()
      assertEquals(false, binding.awaitMap { it.styleSourceInfo(source.id)?.volatileSource })
    }
  }

  @Test
  fun replacement_after_validation_cannot_receive_the_old_handles_writes() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val source = VectorTileSource("tiles", emptyList(), TileSetOptions())
      binding.addSource(source.definition())
      var replaceAfterValidation: (() -> Unit)? = null
      fun handle() =
        binding.handle(source) {
          replaceAfterValidation?.also { replaceAfterValidation = null }?.invoke()
        }
      val old = handle() as VectorTileSourceHandle
      val mutable = assertNotNull(old.asMutable)
      replaceAfterValidation = {
        binding.removeSource(source.id)
        binding.addSource(source.definition())
      }
      mutable.setVolatile(true)
      assertEquals(false, binding.awaitMap { it.styleSourceInfo(source.id)?.volatileSource })

      val oldFeatureHandle = handle() as VectorTileSourceHandle
      replaceAfterValidation = {
        binding.removeSource(source.id)
        binding.addSource(source.definition())
      }
      oldFeatureHandle.setFeatureState("layer", "1", buildJsonObject { put("selected", true) })
      assertEquals(buildJsonObject {}, binding.featureState(source.id, "layer", "1"))
    }
  }

  @Test
  fun a_queued_write_cannot_create_an_identity_for_an_absent_source() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val source = VectorTileSource("tiles", emptyList(), TileSetOptions())
      binding.addSource(source.definition())
      val oldIdentity = binding.identity.sources.get(source.id)
      binding.removeSource(source.id)
      val parked = TestLatch(1)
      val release = TestLatch(1)
      val released = CompletableDeferred<Boolean>()
      val added = CompletableDeferred<Result<Unit>>()
      try {
        assertTrue(
          fixture.session.postOwnerTaskForTest {
            parked.countDown()
            released.complete(release.await(5_000L))
          }
        )
        assertTrue(parked.await(5_000L))
        assertTrue(
          fixture.session.postOwnerTaskForTest {
            added.complete(
              runCatching {
                binding.addSource(source.definition())
                Unit
              }
            )
          }
        )
        // The source is absent when this write is submitted, but installed before it executes.
        binding.postSourceUpdate(source.id, oldIdentity) {
          binding.setSourceVolatile(source.id, true)
        }
      } finally {
        release.countDown()
      }
      assertTrue(released.await(), "native owner gate timed out")
      added.await().getOrThrow()
      assertEquals(false, binding.awaitMap { it.styleSourceInfo(source.id)?.volatileSource })
    }
  }

  @Test
  fun volatility_updates_the_live_source_and_rejects_a_removed_handle(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        val source =
          VectorTileSource(
            "tiles",
            listOf("https://example.invalid/{z}/{x}/{y}.pbf"),
            TileSetOptions(),
          )
        fixture.state.style.sources.add(source)
        val handle = assertNotNull(fixture.state.style.sources[source.id])
        assertEquals(false, handle.isVolatile())
        val mutable = assertNotNull(handle.asMutable)
        mutable.setVolatile(true)
        // A separately acquired handle must see native state, rather than a handle-local copy.
        assertEquals(true, assertNotNull(fixture.state.style.sources[source.id]).isVolatile())
        mutable.setVolatile(false)
        assertEquals(false, handle.isVolatile())
        fixture.state.style.sources[source.id]!!.asMutable!!.remove()
        fixture.state.style.awaitCommands()
        assertFailsWith<IllegalStateException> { handle.isVolatile() }
        assertFailsWith<IllegalStateException> { mutable.setVolatile(true) }
      }
    }

  private fun MlnFfiStyleBinding.handle(
    source: VectorTileSource,
    afterValidation: () -> Unit = {},
  ): SourceHandle {
    val resource = identity.sources.get(source.id)
    return assertNotNull(
      sourceHandle(
        id = source.id,
        definition = null,
        currentDefinition = { null },
        isCurrentResource = {
          val current = identity.sources.isCurrent(source.id, resource)
          if (current) afterValidation()
          current
        },
        operations = ImmediateOperations,
      )
    )
  }

  private object ImmediateOperations : StyleHandleOperationGuard {
    override fun <T> run(action: () -> T): T = action()

    override fun isSourceWritable(id: String): Boolean = true

    override fun isLayerWritable(id: String): Boolean = false

    override fun removeSource(id: String, identity: Any) = error("Unused")

    override fun requireSourceWritable(id: String) {}

    override fun requireLayerWritable(id: String) {}
  }
}
