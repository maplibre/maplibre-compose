package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_equal
import platform.posix.pthread_getname_np
import platform.posix.pthread_self
import platform.posix.pthread_setname_np
import platform.posix.pthread_t
import platform.posix.pthread_tVar

/**
 * The state one owner thread shares with its joiners. The thread keeps it alive through a
 * `StableRef`; a joiner holds it through the [MlnFfiOwnerThread] instance, so a join that times out
 * leaves it allocated for the thread to keep writing to.
 */
private class MlnFfiOwnerThreadContext(val name: String, val body: () -> Unit) {
  val completion = NSCondition()
  var finished = false
}

private val ownerThreadEntry =
  staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
    val reference = argument!!.asStableRef<MlnFfiOwnerThreadContext>()
    val context = reference.get()
    // Darwin's pthread_setname_np names only the calling thread, so the thread names itself
    // first; a crash report from before this line shows an unnamed thread.
    pthread_setname_np(context.name.take(MAX_THREAD_NAME_LENGTH))
    try {
      context.body()
    } catch (error: Throwable) {
      // A Kotlin exception cannot cross the pthread entry boundary, so report it here the way the
      // JVM runtime reports an uncaught thread failure.
      error.printStackTrace()
    } finally {
      context.completion.lock()
      try {
        context.finished = true
        context.completion.broadcast()
      } finally {
        context.completion.unlock()
      }
      reference.dispose()
    }
    null
  }

private const val MAX_THREAD_NAME_LENGTH = 63

internal actual class MlnFfiOwnerThread actual constructor(name: String, body: () -> Unit) {
  private val context = MlnFfiOwnerThreadContext(name, body)
  private var contextReference: StableRef<MlnFfiOwnerThreadContext>? = StableRef.create(context)

  @Volatile private var thread: pthread_t? = null

  actual fun start() {
    val reference = checkNotNull(contextReference) { "The owner thread was already started" }
    memScoped {
      val threadVariable = alloc<pthread_tVar>()
      if (pthread_create(threadVariable.ptr, null, ownerThreadEntry, reference.asCPointer()) != 0) {
        // No body will run, so the StableRef is still this class's to dispose.
        reference.dispose()
        throw IllegalStateException("pthread_create failed for '${context.name}'")
      }
      // The StableRef now belongs to the thread body, which disposes it when the body returns.
      contextReference = null
      thread = threadVariable.value
      // A host that exits while the body still runs leaves the thread behind, so the thread
      // reclaims its own resources rather than a joiner's. Detach after create stands in for
      // pthread_attr_setdetachstate: Kotlin/Native's Darwin platform libraries do not resolve
      // pthread_attr_tVar, so attributes cannot be set from Kotlin without a custom cinterop.
      pthread_detach(threadVariable.value)
    }
  }

  actual fun isCurrent(): Boolean {
    val current = thread ?: return false
    return pthread_equal(current, pthread_self()) != 0
  }

  @OptIn(BetaInteropApi::class)
  actual fun join(timeoutMillis: Long): Boolean {
    context.completion.lock()
    try {
      // A joiner can be a pool-less thread, where an autoreleased NSDate leaks, so the timed
      // wait runs inside a pool of its own.
      return autoreleasepool {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMillis / 1000.0)
        // A condition variable returns from a spurious wakeup as readily as from a signal.
        var timedOut = false
        while (!context.finished && !timedOut) {
          timedOut = !context.completion.waitUntilDate(deadline)
        }
        context.finished
      }
    } finally {
      context.completion.unlock()
    }
  }
}

internal actual fun currentMlnFfiThreadName(): String = memScoped {
  val name = allocArray<ByteVar>(MAX_THREAD_NAME_LENGTH + 1)
  if (pthread_getname_np(pthread_self(), name, (MAX_THREAD_NAME_LENGTH + 1).convert()) != 0) {
    return@memScoped ""
  }
  name.toKString()
}

internal actual fun currentMlnFfiThreadKey(): Any =
  checkNotNull(pthread_self()) { "pthread_self returned null" }
