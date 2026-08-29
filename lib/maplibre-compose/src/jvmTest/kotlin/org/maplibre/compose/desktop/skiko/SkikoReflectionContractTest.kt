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
  fun `the on-screen redrawer exposes its backend renderer`() {
    assertField(Class.forName(SkikoReflection.ON_SCREEN_REDRAWER_CLASS), "renderer")
  }

  @Test
  fun `ComposeWindow exposes the panel the host walks to find the layer`() {
    assertField(Class.forName(SkikoReflection.COMPOSE_WINDOW_CLASS), "composePanel")
  }

  @Test
  fun `the Linux OpenGL redrawer exposes its native and Skia contexts`() {
    val redrawer = Class.forName(SkikoReflection.LINUX_OPENGL_REDRAWER_CLASS)
    assertField(redrawer, "context")
    assertField(redrawer, "glContext")
  }

  @Test
  fun `the Direct3D redrawer exposes its device context and render lock`() {
    val redrawer = Class.forName(SkikoReflection.DIRECT3D_REDRAWER_CLASS)
    assertField(redrawer, "device")
    assertField(redrawer, "context")
    assertField(redrawer, "drawLock")
  }

  @Test
  fun `the Metal redrawer exposes its device context and render lock`() {
    val redrawer = Class.forName(SkikoReflection.METAL_REDRAWER_CLASS)
    assertField(redrawer, "_device")
    assertField(redrawer, "context")
    assertField(redrawer, "drawLock")
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
