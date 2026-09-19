package org.maplibre.compose.map

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding

class MapLifecycleCallbackRaceTest {
  @Test
  fun accepted_callback_delivery_completes_before_closure_commits() = runBlocking {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val binding = state.lifecycle.bind(CallbackRacePlatformAdapter())
    binding.attach()
    val engine = requireNotNull(binding.engineIdentity)
    val callbackEntered = CountDownLatch(1)
    val releaseCallback = CountDownLatch(1)
    val closeStarted = CountDownLatch(1)
    val closeReturned = CountDownLatch(1)

    val callbackThread = thread {
      assertTrue(
        binding.acceptEngineEvent(engine) {
          callbackEntered.countDown()
          assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
        }
      )
    }
    assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
    val closeThread = thread {
      closeStarted.countDown()
      binding.close()
      closeReturned.countDown()
    }

    assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
    assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
    releaseCallback.countDown()
    callbackThread.join()
    assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
    closeThread.join()
    binding.awaitClosed()
    state.close()
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun a_style_action_cannot_silently_target_the_replacement_style() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState(BaseStyle.Demo)
    val adapter = PresentationTestAdapter()
    val token = state.reservePresentation()
    state.publishPresentation(token, adapter)
    val firstStyle = RecordingStyleBinding()
    assertTrue(state.styleAuthority.updateLoadedStyle(adapter, firstStyle))
    assertTrue(runBlocking { state.styleAuthority.markStyleReady(adapter) })
    val actionEntered = CountDownLatch(1)
    val releaseAction = CountDownLatch(1)
    val actionFailure = AtomicReference<Throwable?>()
    val actionThread = thread {
      actionFailure.set(
        runCatching {
          state.styleAuthority.runStyleHandleOperation(firstStyle) {
            actionEntered.countDown()
            assertTrue(releaseAction.await(5, TimeUnit.SECONDS))
            firstStyle.addSource("old-only", JsonObject(emptyMap()))
          }
        }
          .exceptionOrNull()
      )
    }
    assertTrue(actionEntered.await(5, TimeUnit.SECONDS))
    val replacement = RecordingStyleBinding()

    state.styleAuthority.setBaseStyle(BaseStyle.Json("replacement"))
    assertTrue(state.styleAuthority.updateLoadedStyle(adapter, replacement))
    assertTrue(runBlocking { state.styleAuthority.markStyleReady(adapter) })
    releaseAction.countDown()
    actionThread.join()

    assertTrue(actionFailure.get() is IllegalStateException)
    assertTrue(firstStyle.sourceExists("old-only"))
    assertFalse(replacement.sourceExists("old-only"))
    state.close()
    runtime.close()
  }
}

private class CallbackRacePlatformAdapter : MapLifecyclePlatformAdapter {
  override val engineRetention = EngineRetention.RETAIN

  override suspend fun createEngine(identity: EngineMapIdentity) = Unit

  override suspend fun attach(identity: EngineMapIdentity, lease: RenderLease) = Unit

  override suspend fun detach(identity: EngineMapIdentity, lease: RenderLease) = Unit

  override suspend fun destroyEngine(identity: EngineMapIdentity) = Unit

  override suspend fun closeResources() = Unit
}
