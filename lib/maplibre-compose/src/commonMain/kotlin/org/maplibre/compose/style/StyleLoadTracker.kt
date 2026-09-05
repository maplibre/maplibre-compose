package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/** Identifies one base-style request, including a reload after engine destruction. */
internal class StyleRequestId

/** Controls drawing independently from the progress of the requested style. */
internal enum class StylePresentation {
  /** Render to initialize the engine while the surface remains hidden. */
  Hidden,
  /** Display the previous complete frame without rendering. */
  Retained,
  /** Render and display updates. */
  Live,
}

/** Tracks style readiness and preserves the previous presentation during replacement. */
internal class StyleLoadTracker {
  // Requests and completions can arrive on different threads; the renderer reads only presentation.
  private val lock = reentrantLock()
  private var currentRequest = StyleRequestId()
  private var load: Load = Load.Loading

  var presentation by mutableStateOf(StylePresentation.Hidden)
    private set

  val requestId: StyleRequestId
    get() = lock.withLock { currentRequest }

  val isReady: Boolean
    get() = lock.withLock { (load as? Load.Loaded)?.isReady == true }

  fun request(): StyleRequestId = lock.withLock {
    currentRequest = StyleRequestId()
    load = Load.Loading
    retainPresentation()
    currentRequest
  }

  fun resetPresentation() = lock.withLock {
    presentation = StylePresentation.Hidden
  }

  fun engineBecameUnavailable() = lock.withLock {
    request()
    resetPresentation()
  }

  fun loaded(
    request: StyleRequestId,
    identity: StyleIdentity,
    baseStyleReady: Boolean = true,
  ): Boolean = lock.withLock {
    if (request !== currentRequest || load != Load.Loading) return false
    load = Load.Loaded(identity, baseReady = baseStyleReady)
    true
  }

  /** A retained replay also starts here; only the complete current revision can finish it. */
  fun beginReconciliation(identity: StyleIdentity): Boolean = lock.withLock {
    val current = load as? Load.Loaded ?: return false
    if (current.identity !== identity) return false
    load = current.copy(contentReady = false)
    retainPresentation()
    true
  }

  /** Returns true once both the engine and the complete application revision are ready. */
  fun reconciled(identity: StyleIdentity): Boolean = lock.withLock {
    val current = load as? Load.Loaded ?: return false
    if (current.identity !== identity || current.contentReady) return false
    val next = current.copy(contentReady = true)
    load = next
    // Rendering must resume before engine readiness: source loading can depend on rendered frames.
    if (presentation == StylePresentation.Retained || next.isReady) {
      presentation = StylePresentation.Live
    }
    next.isReady
  }

  fun baseStyleReady(identity: StyleIdentity): Boolean = lock.withLock {
    val current = load as? Load.Loaded ?: return false
    if (current.identity !== identity || current.baseReady) return false
    val next = current.copy(baseReady = true)
    load = next
    if (next.isReady) presentation = StylePresentation.Live
    next.isReady
  }

  fun failed(request: StyleRequestId): Boolean = lock.withLock {
    if (request !== currentRequest || load == Load.Failed || isReady) return false
    load = Load.Failed
    presentation = StylePresentation.Hidden
    true
  }

  fun failed(identity: StyleIdentity) = lock.withLock {
    if (beginReconciliation(identity)) {
      presentation = StylePresentation.Hidden
    }
  }

  private fun retainPresentation() {
    if (presentation != StylePresentation.Hidden) presentation = StylePresentation.Retained
  }

  private sealed interface Load {
    data object Loading : Load

    data class Loaded(
      val identity: StyleIdentity,
      val baseReady: Boolean,
      val contentReady: Boolean = false,
    ) : Load {
      val isReady: Boolean
        get() = baseReady && contentReady
    }

    data object Failed : Load
  }
}
