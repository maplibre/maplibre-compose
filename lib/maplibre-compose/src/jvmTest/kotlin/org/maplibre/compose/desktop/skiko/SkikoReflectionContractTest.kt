package org.maplibre.compose.desktop.skiko

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the Compose Desktop and Skiko internals the default host reflects into, so that a Compose
 * upgrade that moves one fails here rather than as a blank map at runtime. Only existence is
 * asserted, so this runs headlessly on any platform whichever backend that platform uses.
 *
 * A failure means Compose or Skiko moved something and `SkikoReflection` needs updating to match.
 */
class SkikoReflectionContractTest {

  @Test
  fun `static invocation accepts the null result of a void method`() {
    assertNull(with(SkikoReflection) { Thread::class.java.staticInvoke("yield") })
  }

  @Test
  fun `SkiaLayer exposes the redrawer and backing layer the host needs`() {
    val skiaLayer = Class.forName(SkikoReflection.SKIA_LAYER_CLASS)
    assertMethod(skiaLayer, "getRedrawer\$skiko")
    assertMethod(skiaLayer, "getWindowHandle")
    assertField(skiaLayer, "backedLayer")
  }

  @Test
  fun `ComposeWindow exposes the panel the host walks to find the layer`() {
    assertField(Class.forName(SkikoReflection.COMPOSE_WINDOW_CLASS), "composePanel")
  }

  @Test
  fun `each redrawer exposes its context handler`() {
    for (redrawer in
      listOf(
        SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS,
        SkikoReflection.METAL_REDRAWER_CLASS,
        SkikoReflection.DIRECT3D_REDRAWER_CLASS,
      )) {
      assertField(Class.forName(redrawer), "contextHandler")
    }
  }

  @Test
  fun `the Linux OpenGL redrawer exposes its native context`() {
    assertField(Class.forName(SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS), "context")
  }

  @Test
  fun `the Direct3D redrawer exposes its device and context factory`() {
    assertField(Class.forName(SkikoReflection.DIRECT3D_REDRAWER_CLASS), "device")
    assertMethod(Class.forName(SkikoReflection.DIRECT3D_CONTEXT_HANDLER_CLASS), "makeContext")
  }

  @Test
  fun `the Metal context handler exposes the device and context the host reads`() {
    // No test drives the Metal host: the suite runs on Vulkan even on macOS.
    assertField(Class.forName(SkikoReflection.METAL_CONTEXT_HANDLER_CLASS), "device")
    assertField(Class.forName(SkikoReflection.CONTEXT_HANDLER_CLASS), "context")
    assertMethod(Class.forName(SkikoReflection.CONTEXT_HANDLER_CLASS), "getContext")
    // Declared abstract on ContextHandler and implemented on ContextBasedContextHandler; the
    // lookup walks superclasses, so asserting on the base is enough.
    assertMethod(Class.forName(SkikoReflection.CONTEXT_HANDLER_CLASS), "initContext")
  }

  @Test
  fun `the Linux drawing surface helpers are callable`() {
    val helpers = Class.forName(SkikoReflection.AWT_LINUX_DRAWING_SURFACE_HELPERS_CLASS)
    assertStaticMethod(helpers, "lockLinuxDrawingSurface", parameterCount = 1)
    assertStaticMethod(helpers, "unlockLinuxDrawingSurface", parameterCount = 1)

    // Skiko generates this synthetic accessor for an internal member; it is the only way to make
    // the window's GL context current from outside Skiko.
    assertStaticMethod(
      Class.forName(SkikoReflection.LINUX_OPENGL_REDRAWER_HELPERS_CLASS),
      "access\$makeCurrent",
      parameterCount = 2,
    )
  }

  private fun assertField(owner: Class<*>, name: String) {
    val found = runCatching { with(SkikoReflection) { owner.findField(name) } }.getOrNull()
    assertNotNull(found, "${owner.name} no longer declares the field '$name'")
  }

  private fun assertMethod(owner: Class<*>, name: String) {
    val found = runCatching { with(SkikoReflection) { owner.findMethod(name) } }.getOrNull()
    assertNotNull(found, "${owner.name} no longer declares the method '$name'")
  }

  private fun assertStaticMethod(owner: Class<*>, name: String, parameterCount: Int) {
    val found = owner.methods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
    assertNotNull(
      found,
      "${owner.name} no longer declares a static '$name' taking $parameterCount argument(s)",
    )
  }
}
