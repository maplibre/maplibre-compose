package org.maplibre.compose.location.desktop.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.ConcurrentHashMap
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.ObjCRuntime

/**
 * Runs work on the AppKit main run loop.
 *
 * Compose Desktop's `Dispatchers.Main` is the Swing event-dispatch thread on the AWT host. That
 * thread does not pump a Cocoa `CFRunLoop`. Core Location delivers delegate callbacks on the run
 * loop of the thread that created the manager, so location and authorization work has to hop here
 * rather than to `Dispatchers.Main`.
 *
 * OpenJDK can wait for the AWT event thread from a nested AppKit run loop during an accessibility
 * query. Work from the AWT thread uses a run-loop selector that the nested loop services. A GCD
 * main-queue dispatch would deadlock both threads.
 *
 * [run] executes inline when the caller is already on the AppKit main thread, because scheduling a
 * run-loop selector there deadlocks. It uses
 * [NSThread isMainThread](https://developer.apple.com/documentation/foundation/nsthread/ismainthread)
 * for this check.
 */
internal object CocoaMain {
  private val linker = Linker.nativeLinker()
  private val stubs = Arena.ofShared()
  private val jobs = ConcurrentHashMap<Long, Runnable>()
  private val jobClass: Long by lazy { registerJobClass() }
  private val runLoopModes: Long by lazy {
    ObjectiveC.allocInit("NSMutableArray").also { modes ->
      OPENJDK_RUN_LOOP_MODES.forEach { mode ->
        ObjectiveC.sendVoid(modes, "addObject:", ObjectiveC.nsString(mode))
      }
    }
  }
  private val workStub: MemorySegment by lazy {
    val handle =
      MethodHandles.lookup()
        .findStatic(
          CocoaMain::class.java,
          "invokeWork",
          MethodType.methodType(
            Void.TYPE,
            MemorySegment::class.java,
            MemorySegment::class.java,
          ),
        )
    linker.upcallStub(handle, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS), stubs)
  }

  fun <T> run(action: () -> T): T = run(isMainThread(), ::performOnMainThread, action)

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

  private fun performOnMainThread(work: Runnable) {
    check(jobClass != NULL)
    val performer = ObjectiveC.allocInit(JOB_CLASS_NAME)
    jobs[performer] = work
    try {
      ObjectiveC.performSelectorOnMainThreadAndWait(
        receiver = performer,
        selectorName = INVOKE_SELECTOR,
        modes = runLoopModes,
      )
    } finally {
      jobs.remove(performer)
      ObjectiveC.release(performer)
    }
  }

  @JvmStatic
  fun invokeWork(
    self: MemorySegment,
    @Suppress("UNUSED_PARAMETER") cmd: MemorySegment,
  ) {
    jobs[self.address()]?.run()
  }

  private fun registerJobClass(): Long {
    ObjectiveC.loadFramework("Foundation")
    val existing = ObjCRuntime.objc_getClass(JOB_CLASS_NAME)
    if (existing != NULL) return existing

    val cls = ObjCRuntime.objc_allocateClassPair(ObjectiveC.cls("NSObject"), JOB_CLASS_NAME, 0)
    check(cls != NULL) { "Failed to allocate $JOB_CLASS_NAME" }
    check(
      ObjCRuntime.class_addMethod(
        cls,
        ObjectiveC.selector(INVOKE_SELECTOR),
        workStub.address(),
        "v@:",
      )
    ) {
      "Failed to add $INVOKE_SELECTOR to $JOB_CLASS_NAME"
    }
    ObjCRuntime.objc_registerClassPair(cls)
    return cls
  }

  private class SyncJob<T>(private val action: () -> T) : Runnable {
    @Volatile private var result: Result<T>? = null

    override fun run() {
      result = runCatching(action)
    }

    fun get(): T = checkNotNull(result) { "AppKit main-thread work did not run" }.getOrThrow()
  }

  private const val JOB_CLASS_NAME = "MLCocoaMainJob"
  private const val INVOKE_SELECTOR = "invoke"
  private val OPENJDK_RUN_LOOP_MODES =
    listOf(
      "kCFRunLoopDefaultMode",
      "NSModalPanelRunLoopMode",
      "NSEventTrackingRunLoopMode",
      "AWTRunLoopMode",
    )
}
