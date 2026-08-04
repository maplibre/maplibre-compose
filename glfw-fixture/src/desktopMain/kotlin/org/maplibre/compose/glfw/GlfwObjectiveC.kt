package org.maplibre.compose.glfw

import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlerror
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.ObjCRuntime

/**
 * The Objective-C messaging the fixture's Metal bridge needs.
 *
 * This is a near-copy of the default host's `MacosObjectiveC`, and the duplication is a finding
 * rather than an oversight: the desktop host SPI is defined entirely in terms of backend-neutral
 * handles, so it exports nothing for talking to a platform graphics API, and neither compose-glfw
 * nor MapLibre Compose publishes its own messaging helper. Any second Metal host therefore starts
 * by writing this file again. Ninety lines is a cheap enough answer that it is not obviously worth
 * fixing, but it is the largest single thing a non-Skiko host has to bring with it.
 *
 * Messages are dispatched by resolving the selector's implementation and calling that function
 * pointer, rather than by calling `objc_msgSend`, which has no single C prototype and on arm64 is
 * not callable through a generic `invokeP…` binding at all.
 */
internal object GlfwObjectiveC {
  private val selectors = mutableMapOf<String, Long>()
  private val classes = mutableMapOf<String, Long>()
  private val frameworks = mutableMapOf<String, Long>()

  fun allocInit(className: String): Long = sendPointer(sendPointer(cls(className), "alloc"), "init")

  fun release(objectAddress: Long) {
    if (objectAddress != NULL) sendVoid(objectAddress, "release")
  }

  /**
   * Opens an autorelease pool that drains when the returned handle is closed.
   *
   * Metal hands back autoreleased objects from most of its factory methods, and the renderer thread
   * this fixture creates has no pool of its own, so without one every object leaks until exit and
   * the runtime logs a warning per object.
   */
  fun autoreleasePool(): AutoreleasePool = AutoreleasePool(allocInit("NSAutoreleasePool"))

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
   * Pointer-sized integers and pointers come back in the same register, so this is the pointer path
   * under a name that reads as what the call site means.
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
      // `objc_getClass` only sees classes already registered, and a JVM that has not touched Metal
      // has not loaded Metal.framework.
      loadFramework(if (name.startsWith("MTL")) "Metal" else "Foundation")
      val value = ObjCRuntime.objc_getClass(name)
      check(value != NULL) { "Objective-C class not found: $name" }
      value
    }

  @Synchronized
  private fun selector(name: String): Long =
    selectors.getOrPut(name) { ObjCRuntime.sel_registerName(name) }

  /**
   * The function pointer implementing [selector] for [receiver].
   *
   * `class_respondsToSelector` is asked first because `class_getMethodImplementation` does not
   * answer it: for a selector the class does not implement it returns the runtime's forwarding
   * trampoline rather than null, and calling that reaches `doesNotRecognizeSelector:`, whose
   * Objective-C exception unwinds through a JNI frame with no handler and aborts the process.
   */
  private fun implementation(receiver: Long, selector: Long): Long {
    check(receiver != NULL) { "Objective-C receiver is null" }
    val objectClass = ObjCRuntime.object_getClass(receiver)
    check(ObjCRuntime.class_respondsToSelector(objectClass, selector)) {
      "Objective-C class ${ObjCRuntime.class_getName(objectClass)} does not respond to " +
        "'${ObjCRuntime.sel_getName(selector)}'"
    }
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
