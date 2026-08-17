package org.maplibre.compose.location.desktop.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlerror
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.DynamicLinkLoader.dlsym

/**
 * Runs work on the AppKit main run loop.
 *
 * Compose Desktop's `Dispatchers.Main` is the Swing event-dispatch thread on the AWT host. That
 * thread does not pump a Cocoa `CFRunLoop`. Core Location delivers delegate callbacks on the run
 * loop of the thread that created the manager, so location and authorization work has to hop here
 * rather than to `Dispatchers.Main`.
 *
 * `dispatch_sync_f` deadlocks if the caller is already on that run loop, so [run] executes inline
 * when
 * [NSThread isMainThread](https://developer.apple.com/documentation/foundation/nsthread/ismainthread)
 * is true.
 */
internal object CocoaMain {
  private val linker = Linker.nativeLinker()
  private val stubs = Arena.ofShared()
  private val jobs = ConcurrentHashMap<Long, Runnable>()
  private val nextId = AtomicLong(1)
  private val dispatchLibrary: Long by lazy {
    val handle = dlopen("/usr/lib/system/libdispatch.dylib", RTLD_NOW or RTLD_LOCAL)
    check(handle != NULL) { "Failed to load libdispatch: ${dlerror()}" }
    handle
  }
  private val mainQueue: MemorySegment by lazy {
    val address = dlsym(dispatchLibrary, "_dispatch_main_q")
    check(address != NULL) { "Failed to resolve _dispatch_main_q: ${dlerror()}" }
    MemorySegment.ofAddress(address)
  }
  private val dispatchSyncF by lazy {
    val address = dlsym(dispatchLibrary, "dispatch_sync_f")
    check(address != NULL) { "Failed to resolve dispatch_sync_f: ${dlerror()}" }
    linker.downcallHandle(
      MemorySegment.ofAddress(address),
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
    )
  }
  private val workStub: MemorySegment by lazy {
    val handle =
      MethodHandles.lookup()
        .findStatic(
          CocoaMain::class.java,
          "invokeWork",
          MethodType.methodType(Void.TYPE, MemorySegment::class.java),
        )
    linker.upcallStub(handle, FunctionDescriptor.ofVoid(ADDRESS), stubs)
  }

  fun <T> run(action: () -> T): T = run(isMainThread(), ::dispatchSyncToMain, action)

  internal fun <T> run(
    alreadyOnMain: Boolean,
    dispatch: (Runnable) -> Unit,
    action: () -> T,
  ): T {
    if (alreadyOnMain) return action()
    val job = SyncJob(action)
    dispatch(job)
    return job.get()
  }

  fun isMainThread(): Boolean {
    ObjectiveC.loadFramework("Foundation")
    return ObjectiveC.sendLong(ObjectiveC.cls("NSThread"), "isMainThread") != 0L
  }

  private fun dispatchSyncToMain(work: Runnable) {
    val id = nextId.incrementAndGet()
    jobs[id] = work
    try {
      dispatchSyncF.invoke(mainQueue, MemorySegment.ofAddress(id), workStub)
    } finally {
      jobs.remove(id)
    }
  }

  @JvmStatic
  fun invokeWork(context: MemorySegment) {
    jobs[context.address()]?.run()
  }

  private class SyncJob<T>(private val action: () -> T) : Runnable {
    private var result: Result<T>? = null

    override fun run() {
      result = runCatching(action)
    }

    fun get(): T = checkNotNull(result) { "AppKit main-queue work did not run" }.getOrThrow()
  }
}
