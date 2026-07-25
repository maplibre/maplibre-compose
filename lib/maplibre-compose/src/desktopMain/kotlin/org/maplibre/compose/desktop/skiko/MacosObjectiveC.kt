package org.maplibre.compose.desktop.skiko

import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlerror
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.ObjCRuntime

/**
 * The little bit of Objective-C messaging the macOS host needs, without a native library of our
 * own.
 *
 * MapLibre Compose ships no JNI code, so the handful of Metal calls the host makes — allocating a
 * texture, reading its pixel format, releasing it — are sent as Objective-C messages through
 * LWJGL's `ObjCRuntime` bindings.
 *
 * Messages are dispatched by looking up the selector's implementation with
 * `class_getMethodImplementation` and calling that function pointer directly, rather than by
 * calling `objc_msgSend`. That is deliberate: `objc_msgSend` has no single C prototype — the
 * correct entry point and calling convention depend on the return type, and on arm64 it is not even
 * callable through a generic `invokeP…` binding. Calling the IMP directly, with the `(self, _cmd,
 * …)` arguments Objective-C would have passed, sidesteps that entirely.
 *
 * Ported from the `maplibre-native-ffi` Compose example, which is the reference for this path.
 */
internal object MacosObjectiveC {
  private val selectors = mutableMapOf<String, Long>()
  private val classes = mutableMapOf<String, Long>()
  private val frameworks = mutableMapOf<String, Long>()

  fun allocInit(className: String): Long = sendPointer(sendPointer(cls(className), "alloc"), "init")

  fun release(objectAddress: Long) {
    if (objectAddress != NULL) {
      sendVoid(objectAddress, "release")
    }
  }

  /**
   * Opens an autorelease pool that drains when the returned handle is closed.
   *
   * Metal returns autoreleased objects from most of its factory methods, and a thread with no pool
   * on its stack leaks them (and logs a warning per object). Every entry point into Objective-C
   * from a thread MapLibre Compose owns wraps itself in one of these.
   */
  fun autoreleasePool(): AutoreleasePool = AutoreleasePool(allocInit("NSAutoreleasePool"))

  /** Runs [action] inside an autorelease pool on the calling thread. */
  fun <T> runInAutoreleasePool(action: () -> T): T = autoreleasePool().use { action() }

  fun sendPointer(receiver: Long, selectorName: String): Long {
    val selector = selector(selectorName)
    return JNI.invokePPP(receiver, selector, implementation(receiver, selector))
  }

  fun sendPointer(receiver: Long, selectorName: String, argument: Long): Long {
    val selector = selector(selectorName)
    return JNI.invokePPPP(receiver, selector, argument, implementation(receiver, selector))
  }

  /**
   * Sends a message returning `NSUInteger`.
   *
   * Pointer-sized integers and pointers come back through the same register, so this is the pointer
   * path under another name; it exists so call sites read as what they mean.
   */
  fun sendLong(receiver: Long, selectorName: String): Long = sendPointer(receiver, selectorName)

  fun sendVoid(receiver: Long, selectorName: String) {
    val selector = selector(selectorName)
    JNI.invokePPV(receiver, selector, implementation(receiver, selector))
  }

  fun sendVoid(receiver: Long, selectorName: String, argument: Long) {
    val selector = selector(selectorName)
    JNI.invokePPPV(receiver, selector, argument, implementation(receiver, selector))
  }

  @Synchronized
  private fun cls(name: String): Long =
    classes.getOrPut(name) {
      loadFrameworkForClass(name)
      val value = ObjCRuntime.objc_getClass(name)
      check(value != NULL) { "Objective-C class not found: $name" }
      value
    }

  /**
   * Ensures the framework defining [className] is loaded before it is looked up.
   *
   * `objc_getClass` only sees classes already registered with the runtime, and a JVM process that
   * has not touched Metal yet has not loaded `Metal.framework`.
   */
  private fun loadFrameworkForClass(className: String) {
    when {
      className.startsWith("MTL") -> loadFramework("Metal")
      else -> loadFramework("Foundation")
    }
  }

  @Synchronized
  private fun selector(name: String): Long =
    selectors.getOrPut(name) { ObjCRuntime.sel_registerName(name) }

  /**
   * The function pointer implementing [selector] for [receiver].
   *
   * `object_getClass` on a class object returns its metaclass, so this resolves class methods such
   * as `alloc` as well as instance methods.
   */
  private fun implementation(receiver: Long, selector: Long): Long {
    check(receiver != NULL) { "Objective-C receiver is null" }
    val objectClass = ObjCRuntime.object_getClass(receiver)
    val implementation = ObjCRuntime.class_getMethodImplementation(objectClass, selector)
    check(implementation != NULL) {
      "Objective-C selector implementation not found: ${ObjCRuntime.sel_getName(selector)}"
    }
    return implementation
  }

  @Synchronized
  private fun loadFramework(framework: String): Long =
    frameworks.getOrPut(framework) {
      val path = "/System/Library/Frameworks/$framework.framework/$framework"
      val handle = dlopen(path, RTLD_NOW or RTLD_LOCAL)
      check(handle != NULL) { "Failed to load framework $path: ${dlerror()}" }
      handle
    }

  /** An `NSAutoreleasePool` scoped to a `use` block. */
  internal class AutoreleasePool(private var pool: Long) : AutoCloseable {
    override fun close() {
      if (pool != NULL) {
        sendVoid(pool, "drain")
        pool = NULL
      }
    }
  }
}
