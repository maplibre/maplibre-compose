@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.internal.CameraInputAuthority

/** Reserves one presentation and aggregates cleanup for one map state's adapter. */
internal class MapLifecycleAuthority(
  private val owner: MapState,
  private val physicalScope: CoroutineScope,
  private val mainDispatcher: CoroutineDispatcher,
  private val mainThread: MainThreadGuard,
) {
  internal val gestureCamera: CameraInputAuthority
    get() = owner.gestureAuthority

  fun requireMain() = mainThread.requireMain()

  fun postToMain(block: () -> Unit) = postToMain(mainDispatcher, mainThread, block)

  private var adapter: MapAdapter? = null
  private var attachment: Attachment? = null
  private val releases = mutableListOf<CompletableDeferred<Result<Unit>>>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private val closeRequested = AtomicBoolean(false)

  val isClosed: Boolean
    get() = closeRequested.load()

  fun close() {
    if (!closeRequested.compareAndSet(expectedValue = false, newValue = true)) return
    postToMain {
      attachment = null
      owner.attachmentAuthority.commitClosed()
      val map = adapter
      map?.close()
      val pending = releases.toList()
      physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
        val failures = mutableListOf<Throwable>()
        pending.forEach { release ->
          release.await().exceptionOrNull()?.let(failures::addCleanupFailure)
        }
        if (map != null) {
          runCatching { map.awaitClosed() }.exceptionOrNull()?.let(failures::addCleanupFailure)
        }
        closure.complete(failures.cleanupResult("Map state"))
        owner.runtime.childClosed(owner)
      }
    }
  }

  suspend fun awaitClosed() = closure.await().getOrThrow()

  fun adapter(create: () -> MapAdapter): MapAdapter {
    requireMain()
    requireOpen()
    return adapter ?: create().also { adopt(it) }
  }

  fun adopt(session: MapAdapter): Boolean {
    requireMain()
    if (isClosed) {
      session.close()
      return false
    }
    check(adapter == null || adapter === session) { "The map state already has an adapter" }
    if (adapter == null) {
      adapter = session
    }
    return true
  }

  fun sessionClosing(session: MapAdapter) = postToMain {
    if (adapter === session) close()
  }

  fun reservePresentation(ownerToken: MapPresentationOwnerToken): MapPresentationToken {
    requireMain()
    requireOpen()
    val current = attachment
    check(current == null || current.releasing || current.owner === ownerToken) {
      "The map state already has a presentation"
    }
    if (current != null && !current.releasing) releasePresentation(current.token, current.adapter)
    val token = MapPresentationToken(nextPresentationToken.incrementAndFetch())
    attachment = Attachment(ownerToken, token)
    return token
  }

  fun isPresentedByOtherOwner(ownerToken: MapPresentationOwnerToken): Boolean {
    requireMain()
    val current = attachment ?: return false
    return !current.releasing && current.owner !== ownerToken
  }

  fun selectAdapterForPresentation(adapter: MapAdapter): Boolean {
    requireMain()
    if (isClosed) return false
    val current = attachment ?: return false
    if (current.releasing || !adopt(adapter)) return false
    if (current.adapter == null && owner.styleAuthority.style.loadState == StyleLoadState.Pending) {
      owner.styleAuthority.beginStyleLoadForNewAdapter()
    }
    current.adapter = adapter
    return true
  }

  fun publishPresentation(token: MapPresentationToken, adapter: MapAdapter) {
    requireMain()
    if (isClosed) return
    val current = attachment
    check(current?.token == token && !current.releasing) {
      "The map presentation reservation is no longer current"
    }
    if (owner.currentMapAttachment?.adapter === adapter) return
    if (!selectAdapterForPresentation(adapter)) return
    try {
      owner.attachmentAuthority.configurePresentationAdapter(adapter)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      owner.styleAuthority.markStyleFailed(adapter, error.message)
    }
    if (isClosed || attachment !== current || current.releasing) return
    owner.attachmentAuthority.commitPresentation(token, adapter)
    owner.attachmentAuthority.seedPresentationViewport(token, adapter)
  }

  fun releasePresentation(token: MapPresentationToken, adapter: MapAdapter?) {
    requireMain()
    val current = attachment ?: return
    if (current.token != token || current.releasing) return
    if (adapter != null && current.adapter !== adapter) return
    current.releasing = true
    owner.attachmentAuthority.clearPresentation()
    val map = current.adapter
    if (map == null) {
      attachment = null
      return
    }
    val completion = CompletableDeferred<Result<Unit>>().also(releases::add)
    // Detach begins inline on main; the native session records it before another attach starts.
    physicalScope.launch(mainDispatcher, start = CoroutineStart.UNDISPATCHED) {
      val result = runCatching { map.detachPresentation() }
      if (attachment === current) attachment = null
      if (result.isSuccess) releases.remove(completion)
      completion.complete(result)
    }
  }

  fun seedCurrentPresentationViewport(adapter: MapAdapter) = postToMain {
    val token =
      attachment?.takeIf { it.adapter === adapter && !it.releasing }?.token ?: return@postToMain
    owner.attachmentAuthority.seedPresentationViewport(token, adapter)
  }

  fun endCurrentPresentationCameraChange(adapter: MapAdapter) = postToMain {
    owner.attachmentAuthority.endCameraChange(adapter)
  }

  fun acceptsAdapter(adapter: MapAdapter): Boolean = !isClosed && this.adapter === adapter

  fun acceptsPresentation(adapter: MapAdapter): Boolean =
    !isClosed && owner.currentMapAttachment?.adapter === adapter

  fun isPendingPublication(adapter: MapAdapter): Boolean =
    !isClosed && attachment?.adapter === adapter && attachment?.releasing == false

  /** GL JS cannot load a style until a host has provided its rendering context. */
  fun mapUnavailable() {
    requireMain()
    owner.styleAuthority.style.loadState = StyleLoadState.Pending
  }

  fun currentAdapter(): MapAdapter? = adapter

  fun presentationAdapterForPlatformAccess(): MapAdapter {
    requireMain()
    requireOpen()
    return checkNotNull(owner.currentMapAttachment?.adapter) {
      "Platform map access requires an attached Web map surface"
    }
  }

  private fun requireOpen() {
    check(!isClosed) { "The map state is closed" }
  }

  private class Attachment(
    val owner: MapPresentationOwnerToken,
    val token: MapPresentationToken,
    var adapter: MapAdapter? = null,
    var releasing: Boolean = false,
  )

  private companion object {
    val nextPresentationToken = AtomicLong(0L)
  }
}

/**
 * Runs [block] on [dispatcher], inline when no dispatch is needed. Either way the block runs on the
 * main thread that [guard] pins, and the guard learns that thread from the first block it runs.
 */
private fun postToMain(dispatcher: CoroutineDispatcher, guard: MainThreadGuard, block: () -> Unit) {
  if (dispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
    dispatcher.dispatch(
      EmptyCoroutineContext,
      Runnable {
        guard.pin()
        block()
      },
    )
  } else {
    guard.requireMain()
    block()
  }
}
