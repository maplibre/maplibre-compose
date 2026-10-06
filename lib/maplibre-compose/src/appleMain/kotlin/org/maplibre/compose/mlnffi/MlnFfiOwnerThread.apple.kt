package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_equal
import platform.posix.pthread_getname_np
import platform.posix.pthread_self
import platform.posix.pthread_setname_np
import platform.posix.pthread_t
import platform.posix.pthread_tVar

/** The started pthread owns this context's StableRef until its body returns. */
private class MlnFfiOwnerThreadContext(val name: String, val body: () -> Unit) {
  @Volatile var thread: pthread_t? = null
}

private val ownerThreadEntry =
  staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
    val reference = argument!!.asStableRef<MlnFfiOwnerThreadContext>()
    val context = reference.get()
    // The child can run before pthread_create returns to the parent.
    context.thread = pthread_self()
    // Darwin's pthread_setname_np names only the calling thread, so the thread names itself
    // first; a crash report from before this line shows an unnamed thread.
    pthread_setname_np(context.name.take(MaxThreadNameLength))
    try {
      context.body()
    } catch (error: Throwable) {
      // A Kotlin exception cannot cross the pthread entry boundary, so report it here the way the
      // JVM runtime reports an uncaught thread failure.
      error.printStackTrace()
    } finally {
      context.thread = null
      reference.dispose()
    }
    null
  }

private const val MaxThreadNameLength = 63

internal actual class MlnFfiOwnerThread actual constructor(name: String, body: () -> Unit) {
  private val context = MlnFfiOwnerThreadContext(name, body)
  private var started = false

  actual fun start() {
    check(!started) { "The owner thread was already started" }
    started = true
    // Created here rather than with this object, so a thread that never starts pins nothing.
    val reference = StableRef.create(context)
    memScoped {
      val threadVariable = alloc<pthread_tVar>()
      if (pthread_create(threadVariable.ptr, null, ownerThreadEntry, reference.asCPointer()) != 0) {
        // No body will run, so the StableRef is still this class's to dispose.
        reference.dispose()
        throw IllegalStateException("pthread_create failed for '${context.name}'")
      }
      // The StableRef now belongs to the thread body, which disposes it when the body returns.
      // A host that exits while the body still runs leaves the thread behind, so the thread
      // reclaims its own resources rather than a joiner's. Detach after create stands in for
      // pthread_attr_setdetachstate: Kotlin/Native's Darwin platform libraries do not resolve
      // pthread_attr_tVar, so attributes cannot be set from Kotlin without a custom cinterop.
      pthread_detach(threadVariable.value)
    }
  }

  actual fun isCurrent(): Boolean {
    val current = context.thread ?: return false
    return pthread_equal(current, pthread_self()) != 0
  }
}

internal actual fun currentMlnFfiThreadName(): String = memScoped {
  val name = allocArray<ByteVar>(MaxThreadNameLength + 1)
  if (pthread_getname_np(pthread_self(), name, (MaxThreadNameLength + 1).convert()) != 0) {
    return@memScoped ""
  }
  name.toKString()
}
