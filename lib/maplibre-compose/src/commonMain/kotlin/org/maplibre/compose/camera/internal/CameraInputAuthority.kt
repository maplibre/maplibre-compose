package org.maplibre.compose.camera.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.CameraInputStart
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapAttachment
import org.maplibre.compose.map.MapState

internal fun interface CameraCommandGuard {
  fun isValid(): Boolean
}

/** The lifecycle lock serializes admission with takeover and completion fences. */
internal class CameraInputAuthority(private val owner: MapState) {
  private var nextId = 0L
  private var cameraGeneration = 0L
  private var inputGeneration = 0L
  private var active: CameraInputToken? = null
  private var configuration = CameraConfiguration()

  /** Callback replacements do not revoke input. Resolved policy and momentum changes do. */
  fun updateConfiguration(value: CameraConfiguration) {
    val previous =
      owner.lifecycle.serialized {
        val changed = configuration.structuralKey != value.structuralKey
        configuration = value
        if (changed) {
          inputGeneration++
          revokeLocked()
        } else null
      }
    previous?.let(::cancelOutsideLock)
  }

  fun origin(token: CameraInputToken): CameraInputOrigin =
    owner.lifecycle.serialized { token.inputOrigin }

  fun setOrigin(token: CameraInputToken, value: CameraInputOrigin) =
    owner.lifecycle.serialized { token.inputOrigin = value }

  fun permitted(token: CameraInputToken, component: CameraComponent): Boolean =
    owner.lifecycle.serialized {
      acceptsLocked(token, enqueue = true) && configuration.enabled(component)
    }

  /** Observe outside the lifecycle lock; observers may take camera authority themselves. */
  fun prepare(token: CameraInputToken, component: CameraComponent): Boolean {
    val callback =
      owner.lifecycle.serialized {
        if (!acceptsLocked(token, enqueue = true) || !configuration.enabled(component)) return false
        if (token.startedComponents.add(component)) configuration.onStart(component) else null
      }
    callback?.invoke(CameraInputStart(token.value, token.origin))
    return permitted(token, component)
  }

  fun rearm(token: CameraInputToken, component: CameraComponent) =
    owner.lifecycle.serialized {
      token.startedComponents.remove(component)
    }

  val generation: Long
    get() = owner.lifecycle.serialized { inputGeneration }

  fun acquire(
    adapter: MapAdapter,
    expectedInputGeneration: Long? = null,
  ): CameraInputToken {
    var previous: CameraInputToken? = null
    val token =
      owner.lifecycle.serialized {
        val attachment = owner.currentMapAttachment
        val target = attachment?.adapter as? CameraInputTarget
        val ready =
          attachment != null &&
            owner.isCurrent(attachment) &&
            attachment.viewport != null &&
            target?.isGestureReady == true &&
            adapter === attachment.adapter &&
            (expectedInputGeneration == null || expectedInputGeneration == inputGeneration)
        val token = CameraInputToken(++nextId, this, attachment, target)
        if (!ready) {
          token.status = CameraInputToken.Status.Cancelled
          token.completion.complete(Unit)
          return@serialized token
        }
        previous = revokeLocked()
        cameraGeneration++
        inputGeneration++
        active = token
        token
      }
    previous?.let(::cancelOutsideLock)
    return token
  }

  /** A delayed click may acquire camera authority only if no newer accepted input intervened. */
  fun acquireIfCurrent(adapter: MapAdapter, generation: Long): CameraInputToken? =
    acquire(adapter, expectedInputGeneration = generation).takeIf { it.acceptsCommands }

  /** Even input with no camera response invalidates an older click's camera fallthrough. */
  fun observeInput(): Long = owner.lifecycle.serialized { ++inputGeneration }

  fun beginProgrammatic(job: Job? = null): CameraCommandGuard {
    var previous: CameraInputToken? = null
    val generation =
      owner.lifecycle.serialized {
        job?.ensureActive()
        check(!owner.isClosed) { "The map state is closed" }
        previous = revokeLocked()
        inputGeneration++
        ++cameraGeneration
      }
    previous?.let(::cancelOutsideLock)
    return CameraCommandGuard {
      owner.lifecycle.serialized {
        !owner.isClosed && cameraGeneration == generation && job?.isActive != false
      }
    }
  }

  fun registerJob(token: CameraInputToken, job: Job) {
    val cancel =
      owner.lifecycle.serialized {
        token.job = job
        token.status == CameraInputToken.Status.Cancelled
      }
    if (cancel) job.cancel(CancellationException("A newer input owns the camera"))
  }

  fun accepts(token: CameraInputToken, enqueue: Boolean): Boolean =
    owner.lifecycle.serialized {
      acceptsLocked(token, enqueue)
    }

  fun enqueue(token: CameraInputToken, action: () -> Unit): Boolean =
    owner.lifecycle.serialized {
      if (!acceptsLocked(token, enqueue = true)) return false
      action()
      true
    }

  fun isCancelled(token: CameraInputToken): Boolean =
    owner.lifecycle.serialized {
      token.status == CameraInputToken.Status.Cancelled
    }

  fun finish(token: CameraInputToken, cancelled: Boolean, enqueue: () -> Unit): Unit =
    owner.lifecycle.serialized {
      if (token.status == CameraInputToken.Status.Completed) return
      if (cancelled) {
        token.status = CameraInputToken.Status.Cancelled
        if (active === token) active = null
      } else if (token.status == CameraInputToken.Status.Open)
        token.status = CameraInputToken.Status.Sealed
      if (!token.finishQueued) {
        token.finishQueued = true
        enqueue()
      }
    }

  fun complete(token: CameraInputToken) =
    owner.lifecycle.serialized {
      if (token.status != CameraInputToken.Status.Cancelled)
        token.status = CameraInputToken.Status.Completed
      if (active === token) active = null
      token.completion.complete(Unit)
    }

  /**
   * Called while invalidating the attachment; cancellation callbacks run outside the owner loop.
   */
  fun detach(attachment: MapAttachment) {
    val token =
      owner.lifecycle.serialized {
        inputGeneration++
        active?.takeIf { it.attachment === attachment }?.also { revokeLocked() }
      } ?: return
    owner.runtime.physicalScope.launch { cancelOutsideLock(token) }
  }

  private fun acceptsLocked(token: CameraInputToken, enqueue: Boolean): Boolean =
    active === token &&
      token.attachment?.let(owner::isCurrent) == true &&
      token.target?.isGestureReady == true &&
      token.job?.isActive != false &&
      (if (enqueue) token.status == CameraInputToken.Status.Open
      else
        token.status == CameraInputToken.Status.Open ||
          token.status == CameraInputToken.Status.Sealed)

  private fun revokeLocked(): CameraInputToken? = active?.also {
    it.status = CameraInputToken.Status.Cancelled
    active = null
  }

  private fun cancelOutsideLock(token: CameraInputToken) {
    owner.lifecycle
      .serialized { token.job }
      ?.cancel(CancellationException("A newer input owns the camera"))
    token.target?.cancelGesture(token)
  }
}
