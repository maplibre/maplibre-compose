package org.maplibre.compose.map

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.RecordingStyleBinding

class MapLifecycleCallbackRaceTest {
  @Test
  fun accepted_callback_delivery_completes_before_closure_commits() = runBlocking {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
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
  fun a_late_platform_style_write_replays_the_latest_durable_style() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val firstStyle = BaseStyle.Json("first")
    val secondStyle = BaseStyle.Json("second")
    val adapter = BlockingStyleAdapter(firstStyle)
    val token = state.reservePresentation()
    state.publishPresentation(token, adapter)

    val firstThread = thread { state.setBaseStyle(firstStyle) }
    assertTrue(adapter.firstWriteEntered.await(5, TimeUnit.SECONDS))
    val secondStarted = CountDownLatch(1)
    val secondFinished = CountDownLatch(1)
    val secondThread = thread {
      secondStarted.countDown()
      state.setBaseStyle(secondStyle)
      secondFinished.countDown()
    }

    assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
    assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
    assertEquals(secondStyle, state.style.baseStyle)
    adapter.releaseFirstWrite.countDown()
    firstThread.join()
    secondThread.join()

    assertEquals(secondStyle, state.style.baseStyle)
    assertEquals(secondStyle, adapter.lastStyle)
    state.close()
    runtime.close()
  }

  @Test
  fun a_late_camera_write_replays_the_latest_durable_camera() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val adapter = BlockingCameraAdapter()
    val token = state.reservePresentation()
    state.publishPresentation(token, adapter)
    val presentation = requireNotNull(state.presentation)
    val first = CameraPosition(zoom = 4.0)
    val second = CameraPosition(zoom = 8.0)
    adapter.blockNextWrite = true

    val firstThread = thread { presentation.setCameraPosition(first) }
    assertTrue(adapter.blockedWriteEntered.await(5, TimeUnit.SECONDS))
    val secondThread = thread { presentation.setCameraPosition(second) }
    secondThread.join()
    assertEquals(second, state.cameraPosition)

    adapter.releaseBlockedWrite.countDown()
    firstThread.join()

    assertEquals(second, state.cameraPosition)
    assertEquals(second, adapter.lastCamera)
    state.close()
    runtime.close()
  }

  @Test
  fun presentation_configuration_replays_a_newer_style_after_a_late_initial_write() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val adapter = BlockingStyleAdapter(BaseStyle.Demo)
    val token = state.reservePresentation()
    val publicationFinished = CountDownLatch(1)
    val publicationThread = thread {
      state.publishPresentation(token, adapter)
      publicationFinished.countDown()
    }
    assertTrue(adapter.firstWriteEntered.await(5, TimeUnit.SECONDS))
    val latest = BaseStyle.Json("latest")

    state.setBaseStyle(latest)
    adapter.releaseFirstWrite.countDown()
    assertTrue(publicationFinished.await(5, TimeUnit.SECONDS))
    publicationThread.join()

    assertEquals(latest, state.style.baseStyle)
    assertEquals(latest, adapter.lastStyle)
    state.close()
    runtime.close()
  }

  @Test
  fun departed_presentation_configuration_cannot_write_its_style() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val owner = MapPresentationOwnerToken()
    val first = BlockingConfigurationAdapter()
    val firstToken = state.reservePresentation(owner)
    val firstPublicationFinished = CountDownLatch(1)
    val firstPublicationThread = thread {
      state.publishPresentation(firstToken, first)
      firstPublicationFinished.countDown()
    }
    assertTrue(first.cameraWriteEntered.await(5, TimeUnit.SECONDS))
    val replacement = PresentationTestAdapter()
    val replacementToken = state.reservePresentation(owner)

    state.publishPresentation(replacementToken, replacement)
    first.releaseCameraWrite.countDown()
    assertTrue(firstPublicationFinished.await(5, TimeUnit.SECONDS))
    firstPublicationThread.join()

    assertEquals(0, first.styleWrites)
    assertTrue(state.presentation?.adapter === replacement)
    state.close()
    runtime.close()
  }

  @Test
  fun a_style_action_cannot_silently_target_the_replacement_style() {
    val runtime = mapRuntimeForTest()
    val state = runtime.createMapState()
    val adapter = PresentationTestAdapter()
    val token = state.reservePresentation()
    state.publishPresentation(token, adapter)
    val firstStyle = RecordingStyleBinding()
    assertTrue(state.updateLoadedStyle(adapter, firstStyle))
    assertTrue(state.markStyleReady(adapter))
    val actionEntered = CountDownLatch(1)
    val releaseAction = CountDownLatch(1)
    val actionFailure = AtomicReference<Throwable?>()
    val actionThread = thread {
      actionFailure.set(
        runCatching {
          state.runStyleHandleOperation(firstStyle) {
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

    state.setBaseStyle(BaseStyle.Json("replacement"))
    assertTrue(state.updateLoadedStyle(adapter, replacement))
    assertTrue(state.markStyleReady(adapter))
    releaseAction.countDown()
    actionThread.join()

    assertTrue(actionFailure.get() is IllegalStateException)
    assertTrue(firstStyle.sourceExists("old-only"))
    assertFalse(replacement.sourceExists("old-only"))
    state.close()
    runtime.close()
  }
}

private class BlockingStyleAdapter(private val blockedStyle: BaseStyle) :
  PresentationTestAdapter() {
  val firstWriteEntered = CountDownLatch(1)
  val releaseFirstWrite = CountDownLatch(1)
  var lastStyle: BaseStyle? = null

  override fun setBaseStyle(style: BaseStyle) {
    if (style == blockedStyle) {
      firstWriteEntered.countDown()
      assertTrue(releaseFirstWrite.await(5, TimeUnit.SECONDS))
    }
    lastStyle = style
  }
}

private class BlockingCameraAdapter : PresentationTestAdapter() {
  val blockedWriteEntered = CountDownLatch(1)
  val releaseBlockedWrite = CountDownLatch(1)
  @Volatile var blockNextWrite = false
  @Volatile var lastCamera = CameraPosition()

  override fun setCameraPosition(cameraPosition: CameraPosition) {
    if (blockNextWrite) {
      blockNextWrite = false
      blockedWriteEntered.countDown()
      assertTrue(releaseBlockedWrite.await(5, TimeUnit.SECONDS))
    }
    lastCamera = cameraPosition
  }
}

private class BlockingConfigurationAdapter : PresentationTestAdapter() {
  val cameraWriteEntered = CountDownLatch(1)
  val releaseCameraWrite = CountDownLatch(1)
  var styleWrites = 0

  override fun setCameraPosition(cameraPosition: org.maplibre.compose.camera.CameraPosition) {
    cameraWriteEntered.countDown()
    assertTrue(releaseCameraWrite.await(5, TimeUnit.SECONDS))
  }

  override fun setBaseStyle(style: BaseStyle) {
    styleWrites++
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
