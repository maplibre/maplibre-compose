package org.maplibre.compose.desktop.skiko

import kotlin.test.Test
import kotlin.test.assertTrue
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.ObjCRuntime

/**
 * Pins the Objective-C members of Skiko's Metal device wrapper that the macOS host messages. That
 * class is private to Skiko's `MetalRedrawer.mm`, appears in no header, and would be renamed
 * silently by a Skiko upgrade — so a failure here means update [SkikoComposeGpuHost], not a bug.
 *
 * The Objective-C runtime answers this without a GPU, a window, or a Skia context. Off macOS the
 * body is skipped rather than the test, so no machine reports a routine skip.
 */
class SkikoMetalDeviceContractTest {

  @Test
  fun `Skiko's Metal device wrapper still exposes the adapter property`() {
    if (!isMacos()) return

    val skikoVersion = loadSkikoNativeLibrary()

    val deviceClass = ObjCRuntime.objc_getClass(SkikoReflection.SKIKO_METAL_DEVICE_CLASS)
    assertTrue(
      deviceClass != NULL,
      "Skiko $skikoVersion no longer registers the Objective-C class " +
        "'${SkikoReflection.SKIKO_METAL_DEVICE_CLASS}'. The macOS map host reads Compose's " +
        "MTLDevice out of it; update SkikoComposeGpuHost.",
    )

    val adapter = ObjCRuntime.sel_registerName(SkikoReflection.SKIKO_METAL_DEVICE_ADAPTER)
    assertTrue(
      ObjCRuntime.class_respondsToSelector(deviceClass, adapter),
      "Skiko $skikoVersion's '${SkikoReflection.SKIKO_METAL_DEVICE_CLASS}' no longer responds " +
        "to '${SkikoReflection.SKIKO_METAL_DEVICE_ADAPTER}'. The macOS map host sends that to " +
        "reach the MTLDevice it allocates its texture on; update SkikoComposeGpuHost.",
    )

    // The name surviving is not enough; the property has to still be the device. Objective-C
    // records the declared type in the attribute string: `T@"<MTLDevice>",&,V_adapter` here.
    val property =
      ObjCRuntime.class_getProperty(deviceClass, SkikoReflection.SKIKO_METAL_DEVICE_ADAPTER)
    val attributes =
      property.takeIf { it != NULL }?.let { ObjCRuntime.property_getAttributes(it) }.orEmpty()
    assertTrue(
      attributes.contains("@\"<MTLDevice>\""),
      "Skiko $skikoVersion declares '${SkikoReflection.SKIKO_METAL_DEVICE_ADAPTER}' as " +
        "'$attributes', not as an id<MTLDevice>. The macOS map host allocates its texture on " +
        "whatever this returns.",
    )
  }

  private fun isMacos(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

  /**
   * Loads Skiko's native library and returns the version that supplied it. The class metadata this
   * test reads is registered with the Objective-C runtime by `dlopen`, so it must be loaded first.
   */
  private fun loadSkikoNativeLibrary(): String {
    val library = Class.forName("org.jetbrains.skiko.Library")
    library.getMethod("load").invoke(library.getField("INSTANCE").get(null))
    val version = Class.forName("org.jetbrains.skiko.Version")
    return version.getMethod("getSkiko").invoke(version.getField("INSTANCE").get(null)) as String
  }
}
