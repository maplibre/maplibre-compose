package org.maplibre.compose.location.desktop.macos

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_LOCAL
import org.lwjgl.system.macosx.DynamicLinkLoader.RTLD_NOW
import org.lwjgl.system.macosx.DynamicLinkLoader.dlerror
import org.lwjgl.system.macosx.DynamicLinkLoader.dlopen
import org.lwjgl.system.macosx.DynamicLinkLoader.dlsym
import org.lwjgl.system.macosx.ObjCRuntime

/**
 * Objective-C messaging for the macOS location backend, without a native library of our own.
 *
 * Messages are dispatched by calling the selector's implementation directly rather than through
 * `objc_msgSend`, which has no single C prototype.
 */
internal object ObjectiveC {
  private val linker = Linker.nativeLinker()
  private val selectors = mutableMapOf<String, Long>()
  private val classes = mutableMapOf<String, Long>()
  private val frameworks = mutableMapOf<String, Long>()
  private val coordinateLayout =
    MemoryLayout.structLayout(
      JAVA_DOUBLE.withName("latitude"),
      JAVA_DOUBLE.withName("longitude"),
    )

  data class Coordinate(val latitude: Double, val longitude: Double)

  fun allocInit(className: String): Long = sendPointer(sendPointer(cls(className), "alloc"), "init")

  fun release(objectAddress: Long) {
    if (objectAddress != NULL) {
      sendVoid(objectAddress, "release")
    }
  }

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

  fun sendLong(receiver: Long, selectorName: String): Long = sendPointer(receiver, selectorName)

  fun sendDouble(receiver: Long, selectorName: String): Double {
    val selector = selector(selectorName)
    return JNI.invokePPD(receiver, selector, implementation(receiver, selector))
  }

  fun sendVoid(receiver: Long, selectorName: String) {
    val selector = selector(selectorName)
    JNI.invokePPV(receiver, selector, implementation(receiver, selector))
  }

  fun sendVoid(receiver: Long, selectorName: String, argument: Long) {
    val selector = selector(selectorName)
    JNI.invokePPPV(receiver, selector, argument, implementation(receiver, selector))
  }

  fun sendVoid(receiver: Long, selectorName: String, argument1: Long, argument2: Long) {
    val selector = selector(selectorName)
    val handle =
      linker.downcallHandle(
        MemorySegment.ofAddress(implementation(receiver, selector)),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS),
      )
    handle.invoke(
      MemorySegment.ofAddress(receiver),
      MemorySegment.ofAddress(selector),
      MemorySegment.ofAddress(argument1),
      MemorySegment.ofAddress(argument2),
    )
  }

  fun sendVoidDouble(receiver: Long, selectorName: String, argument: Double) {
    val selector = selector(selectorName)
    val implementation = implementation(receiver, selector)
    val handle =
      linker.downcallHandle(
        MemorySegment.ofAddress(implementation),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE),
      )
    handle.invoke(MemorySegment.ofAddress(receiver), MemorySegment.ofAddress(selector), argument)
  }

  fun sendCoordinate(receiver: Long, selectorName: String): Coordinate {
    val selector = selector(selectorName)
    val implementation = implementation(receiver, selector)
    val handle =
      linker.downcallHandle(
        MemorySegment.ofAddress(implementation),
        FunctionDescriptor.of(coordinateLayout, ADDRESS, ADDRESS),
      )
    Arena.ofConfined().use { arena ->
      val result =
        handle.invoke(
          arena,
          MemorySegment.ofAddress(receiver),
          MemorySegment.ofAddress(selector),
        ) as MemorySegment
      return Coordinate(
        latitude = result.get(JAVA_DOUBLE, 0),
        longitude = result.get(JAVA_DOUBLE, JAVA_DOUBLE.byteSize()),
      )
    }
  }

  fun utf8String(nsString: Long): String {
    if (nsString == NULL) return ""
    val utf8 = sendPointer(nsString, "UTF8String")
    return if (utf8 == NULL) "" else MemoryUtil.memUTF8(utf8)
  }

  fun nsString(value: String): Long =
    MemoryStack.stackPush().use { stack ->
      sendPointer(
        cls("NSString"),
        "stringWithUTF8String:",
        MemoryUtil.memAddress(stack.UTF8(value)),
      )
    }

  fun mainBundlePath(): String? {
    val bundle = sendPointer(cls("NSBundle"), "mainBundle")
    if (bundle == NULL) return null
    val path = sendPointer(bundle, "bundlePath")
    if (path == NULL) return null
    return utf8String(path).ifEmpty { null }
  }

  fun infoDictionaryString(key: String): String? {
    val bundle = sendPointer(cls("NSBundle"), "mainBundle")
    if (bundle == NULL) return null
    val value = sendPointer(bundle, "objectForInfoDictionaryKey:", nsString(key))
    if (value == NULL) return null
    return utf8String(value).ifEmpty { null }
  }

  fun infoPlistString(key: String): String? {
    val bundlePath = mainBundlePath() ?: return null
    val plist =
      sendPointer(
        cls("NSDictionary"),
        "dictionaryWithContentsOfFile:",
        nsString("$bundlePath/Contents/Info.plist"),
      )
    if (plist == NULL) return null
    val value = sendPointer(plist, "objectForKey:", nsString(key))
    if (value == NULL) return null
    return utf8String(value).ifEmpty { null }
  }

  fun putInfoDictionaryString(key: String, value: String): Boolean {
    val bundle = sendPointer(cls("NSBundle"), "mainBundle")
    if (bundle == NULL) return false
    val info = sendPointer(bundle, "infoDictionary")
    if (info == NULL) return false
    if (sendPointer(info, "isKindOfClass:", cls("NSMutableDictionary")) == 0L) return false
    sendVoid(info, "setObject:forKey:", nsString(value), nsString(key))
    return infoDictionaryString(key) != null
  }

  fun respondsTo(receiver: Long, selectorName: String): Boolean {
    val objectClass = ObjCRuntime.object_getClass(receiver)
    return objectClass != NULL &&
      ObjCRuntime.class_respondsToSelector(objectClass, selector(selectorName))
  }

  @Synchronized
  fun cls(name: String): Long =
    classes.getOrPut(name) {
      loadFrameworkForClass(name)
      val value = ObjCRuntime.objc_getClass(name)
      check(value != NULL) { "Objective-C class not found: $name" }
      value
    }

  @Synchronized
  fun selector(name: String): Long = selectors.getOrPut(name) { ObjCRuntime.sel_registerName(name) }

  @Synchronized
  fun loadFramework(framework: String): Long =
    frameworks.getOrPut(framework) {
      val path = "/System/Library/Frameworks/$framework.framework/$framework"
      val handle = dlopen(path, RTLD_NOW or RTLD_LOCAL)
      check(handle != NULL) { "Failed to load framework $path: ${dlerror()}" }
      handle
    }

  fun exportedDoubleOrNull(symbol: String): Double? {
    for (framework in listOf("CoreLocation", "_LocationEssentials")) {
      val handle =
        try {
          loadFramework(framework)
        } catch (_: Throwable) {
          continue
        }
      val address = dlsym(handle, symbol)
      if (address == NULL) continue
      return MemorySegment.ofAddress(address)
        .reinterpret(JAVA_DOUBLE.byteSize())
        .get(JAVA_DOUBLE, 0)
    }
    return null
  }

  private fun loadFrameworkForClass(className: String) {
    when {
      className.startsWith("CL") || className == DELEGATE_CLASS_NAME -> {
        loadFramework("Foundation")
        loadFramework("CoreLocation")
      }
      else -> loadFramework("Foundation")
    }
  }

  private fun implementation(receiver: Long, selector: Long): Long {
    check(receiver != NULL) { "Objective-C receiver is null" }
    val objectClass = ObjCRuntime.object_getClass(receiver)
    if (!ObjCRuntime.class_respondsToSelector(objectClass, selector)) {
      throw IllegalStateException(
        "Objective-C class ${ObjCRuntime.class_getName(objectClass)} does not respond to " +
          "'${ObjCRuntime.sel_getName(selector)}'"
      )
    }
    val implementation = ObjCRuntime.class_getMethodImplementation(objectClass, selector)
    check(implementation != NULL) {
      "Objective-C selector implementation not found: ${ObjCRuntime.sel_getName(selector)}"
    }
    return implementation
  }

  internal const val DELEGATE_CLASS_NAME = "MLCLocationDelegate"

  internal class AutoreleasePool(private var pool: Long) : AutoCloseable {
    override fun close() {
      if (pool != NULL) {
        sendVoid(pool, "drain")
        pool = NULL
      }
    }
  }
}
