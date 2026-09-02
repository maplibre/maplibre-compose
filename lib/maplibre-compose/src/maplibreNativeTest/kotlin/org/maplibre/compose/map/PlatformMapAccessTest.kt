package org.maplibre.compose.map

import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.style.BaseStyle
import org.maplibre.nativeffi.map.MapHandle

@OptIn(DelicateMapApi::class)
class PlatformMapAccessTest {
  @Test
  fun detached_native_access_creates_the_map_and_runs_on_its_owner_context() = runBlocking {
    withNativeMapState { state, _ ->
      val callerThread = withContext(Dispatchers.Default) { currentMlnFfiThreadName() }

      val callbackThread =
        withContext(Dispatchers.Default) {
          state.withPlatformMap {
            val rawMap: MapHandle = map
            rawMap.hashCode()
            currentMlnFfiThreadName()
          }
        }

      assertEquals("maplibre-compose-map", callbackThread)
      assertTrue(callbackThread != callerThread)
      assertNull(state.currentMapAttachment)
    }
  }

  @Test
  fun closure_waits_for_a_started_native_callback_and_later_access_is_rejected() = runBlocking {
    withNativeMapState { state, _ ->
      val callbackStarted = CompletableDeferred<Unit>()
      val releaseCallback = MlnFfiGate()
      val access =
        async(Dispatchers.Default) {
          state.withPlatformMap {
            callbackStarted.complete(Unit)
            releaseCallback.awaitUntilOpen()
            map.hashCode()
            true
          }
        }
      callbackStarted.await()

      val closeStarted = CompletableDeferred<Unit>()
      val closeReturned = CompletableDeferred<Unit>()
      val close =
        async(Dispatchers.Default) {
          closeStarted.complete(Unit)
          state.close()
          closeReturned.complete(Unit)
        }
      closeStarted.await()

      assertFalse(closeReturned.isCompleted)
      assertFalse(state.isClosed)
      releaseCallback.open()
      assertTrue(access.await())
      close.await()
      assertTrue(state.isClosed)
      state.awaitClosed()

      var callbackRan = false
      assertFailsWith<MapStateClosedException> {
        state.withPlatformMap {
          callbackRan = true
          map
        }
      }
      assertFalse(callbackRan)
    }
  }

  @Test
  fun closure_requested_inside_a_native_callback_commits_after_it_returns() = runBlocking {
    withNativeMapState { state, _ ->
      val result = state.withPlatformMap {
        state.close()
        assertFalse(state.isClosed)
        map.hashCode()
        true
      }

      assertTrue(state.isClosed)
      state.awaitClosed()
      assertTrue(result)
    }
  }

  @Test
  fun replacing_the_engine_before_a_queued_native_callback_rejects_it() = runBlocking {
    withNativeMapState { state, runtime ->
      state.withPlatformMap { map.hashCode() }
      val original = state.lifecycle.currentAdapter() as MlnFfiMapSession
      val ownerEntered = CompletableDeferred<Unit>()
      val releaseOwner = MlnFfiGate()
      assertTrue(
        original.postOwnerTaskForTest {
          ownerEntered.complete(Unit)
          releaseOwner.awaitUntilOpen()
        }
      )
      ownerEntered.await()

      var callbackRan = false
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            state.withPlatformMap {
              callbackRan = true
              map.hashCode()
            }
          }

        val token = state.reservePresentation()
        val options = runtime.nativeRuntimeOptions
        val replacement =
          MlnFfiMapSession(
            lifecycleAuthority = state.lifecycle,
            callbacks = state.durableStyleCallbacks(),
            logger = runtime.logger,
            renderBackend = MapRenderBackend.OPENGL,
            scaleFactor = 2.0,
            layoutDirection = LayoutDirection.Ltr,
            cacheFile = options.cacheFile,
            resourceProviderFactory = options.resourceProviderFactory,
          )
        state.publishPresentation(token, replacement)
        releaseOwner.open()

        val failure = assertFailsWith<IllegalStateException> { access.await() }
        assertEquals("The native platform map changed before access could begin", failure.message)
      }
      assertFalse(callbackRan)
    }
  }

  @Test
  fun cancelling_a_queued_native_invocation_prevents_its_callback() = runBlocking {
    withNativeMapState { state, _ ->
      state.withPlatformMap { map.hashCode() }
      val session = state.lifecycle.currentAdapter() as MlnFfiMapSession
      val ownerEntered = CompletableDeferred<Unit>()
      val releaseOwner = MlnFfiGate()
      assertTrue(
        session.postOwnerTaskForTest {
          ownerEntered.complete(Unit)
          releaseOwner.awaitUntilOpen()
        }
      )
      ownerEntered.await()

      var callbackRan = false
      try {
        supervisorScope {
          val access =
            async(start = CoroutineStart.UNDISPATCHED) {
              state.withPlatformMap {
                callbackRan = true
                map.hashCode()
              }
            }
          val ownerDrained = CompletableDeferred<Unit>()
          assertTrue(session.postOwnerTaskForTest { ownerDrained.complete(Unit) })

          access.cancel()
          assertFailsWith<CancellationException> { access.await() }
          releaseOwner.open()
          ownerDrained.await()
        }
      } finally {
        releaseOwner.open()
      }
      assertFalse(callbackRan)
    }
  }

  private suspend fun withNativeMapState(block: suspend (MapState, RuntimeImplementation) -> Unit) {
    FfiTestPlatform.initialize()
    val cacheFile = FfiTestPlatform.createCacheFile()
    val runtime =
      RuntimeImplementation(
        platformOptions = MlnFfiRuntimeOptions(cacheFile),
        resources = MapRuntimeResources {},
        logger = null,
      )
    val state = runtime.createMapState(baseStyle = BaseStyle.Empty)
    try {
      block(state, runtime)
    } finally {
      runtime.close()
      runtime.awaitClosed()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }
}
