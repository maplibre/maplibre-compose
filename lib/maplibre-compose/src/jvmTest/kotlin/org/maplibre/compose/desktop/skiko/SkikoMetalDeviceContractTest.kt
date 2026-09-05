package org.maplibre.compose.desktop.skiko

import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.ObjCRuntime

/**
 * Checks the Objective-C members used by [AwtComposeMapPresentationHost]. Skiko's private
 * `MetalRedrawer.mm` wrapper has no header; an upgrade may require updating the host.
 *
 * The Objective-C runtime can check these members without a GPU, window, or Skia context.
 */
class SkikoMetalDeviceContractTest {

  @Test
  fun `Skiko's Metal device wrapper still exposes the adapter property`() =
    onMacos("the Objective-C runtime this interrogates exists only on macOS") {
      val skikoVersion = loadSkikoNativeLibrary()

      val deviceClass = ObjCRuntime.objc_getClass(SkikoReflection.SKIKO_METAL_DEVICE_CLASS)
      assertTrue(
        deviceClass != NULL,
        "Skiko $skikoVersion no longer registers the Objective-C class " +
          "'${SkikoReflection.SKIKO_METAL_DEVICE_CLASS}'. The macOS map host reads Compose's " +
          "MTLDevice out of it; update AwtComposeMapPresentationHost.",
      )

      val adapter = ObjCRuntime.sel_registerName(SkikoReflection.SKIKO_METAL_DEVICE_ADAPTER)
      assertTrue(
        ObjCRuntime.class_respondsToSelector(deviceClass, adapter),
        "Skiko $skikoVersion's '${SkikoReflection.SKIKO_METAL_DEVICE_CLASS}' no longer responds " +
          "to '${SkikoReflection.SKIKO_METAL_DEVICE_ADAPTER}'. The macOS map host sends that to " +
          "reach the MTLDevice it allocates its texture on; update AwtComposeMapPresentationHost.",
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

  /**
   * Runs [block] on macOS only, skipping elsewhere rather than reporting a pass it did not earn.
   */
  private inline fun onMacos(reason: String, block: () -> Unit) {
    assumeTrue(reason, System.getProperty("os.name").orEmpty().lowercase().contains("mac"))
    block()
  }

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
