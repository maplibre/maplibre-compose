package org.maplibre.compose.desktop.skiko

import kotlin.test.Test
import kotlin.test.assertTrue
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.macosx.ObjCRuntime

/**
 * Pins the Objective-C members of Skiko's Metal device wrapper that the macOS host messages.
 *
 * [SkikoReflectionContractTest] does this for the Java members the bridge reflects into, but the
 * macOS bridge does not stop at Java: `SkikoReflection.requireMetalDevice` yields a pointer to
 * Skiko's own Objective-C object, and the `id<MTLDevice>` the host allocates its texture on is read
 * out of that by sending it `adapter`. That class is private to Skiko's `MetalRedrawer.mm`, appears
 * in no header or published API, and would be renamed silently by a Skiko upgrade.
 *
 * The Objective-C runtime can answer this without a GPU, a window, or a Skia context: loading
 * Skiko's native library registers its classes, and `class_respondsToSelector` is then a pure
 * lookup. So this runs in the ordinary headless suite even though nothing else exercises the Metal
 * host — the desktop tests deliberately run on Vulkan.
 *
 * Off macOS there is no Skiko dylib carrying these symbols and nothing to assert, so the body is
 * skipped rather than the test: a suite that reports a skip on every non-macOS machine trains
 * people to ignore skips.
 *
 * A failure here is not necessarily a bug in MapLibre Compose. It means Skiko renamed something,
 * and [MacosMetalTexture] needs updating to match.
 */
class MacosMetalDeviceContractTest {

  @Test
  fun `Skiko's Metal device wrapper still exposes the adapter property`() {
    if (!isMacos()) return

    val skikoVersion = loadSkikoNativeLibrary()

    val deviceClass = ObjCRuntime.objc_getClass(MacosMetalTexture.SKIKO_METAL_DEVICE_CLASS)
    assertTrue(
      deviceClass != NULL,
      "Skiko $skikoVersion no longer registers the Objective-C class " +
        "'${MacosMetalTexture.SKIKO_METAL_DEVICE_CLASS}'. The macOS map host reads Compose's " +
        "MTLDevice out of it; update MacosMetalTexture.",
    )

    val adapter = ObjCRuntime.sel_registerName(MacosMetalTexture.SKIKO_METAL_DEVICE_ADAPTER)
    assertTrue(
      ObjCRuntime.class_respondsToSelector(deviceClass, adapter),
      "Skiko $skikoVersion's '${MacosMetalTexture.SKIKO_METAL_DEVICE_CLASS}' no longer responds " +
        "to '${MacosMetalTexture.SKIKO_METAL_DEVICE_ADAPTER}'. The macOS map host sends that to " +
        "reach the MTLDevice it allocates its texture on; update MacosMetalTexture.",
    )

    // The name surviving is not enough: the host hands what comes back to
    // `newTextureWithDescriptor:`, so the property has to still be the device and not, say, the
    // command queue. Objective-C records the declared type in the property attribute string, which
    // is `T@"<MTLDevice>",&,V_adapter` for this one.
    val property =
      ObjCRuntime.class_getProperty(deviceClass, MacosMetalTexture.SKIKO_METAL_DEVICE_ADAPTER)
    val attributes =
      property.takeIf { it != NULL }?.let { ObjCRuntime.property_getAttributes(it) }.orEmpty()
    assertTrue(
      attributes.contains("@\"<MTLDevice>\""),
      "Skiko $skikoVersion declares '${MacosMetalTexture.SKIKO_METAL_DEVICE_ADAPTER}' as " +
        "'$attributes', not as an id<MTLDevice>. The macOS map host allocates its texture on " +
        "whatever this returns.",
    )
  }

  private fun isMacos(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

  /**
   * Loads Skiko's native library and returns the version that supplied it.
   *
   * The class metadata this test reads is registered with the Objective-C runtime by `dlopen`, so
   * the library has to be in the process first. `Library.load` only unpacks and loads it; it starts
   * no window and creates no device, which is why this stays a headless test.
   */
  private fun loadSkikoNativeLibrary(): String {
    val library = Class.forName("org.jetbrains.skiko.Library")
    library.getMethod("load").invoke(library.getField("INSTANCE").get(null))
    val version = Class.forName("org.jetbrains.skiko.Version")
    return version.getMethod("getSkiko").invoke(version.getField("INSTANCE").get(null)) as String
  }
}
