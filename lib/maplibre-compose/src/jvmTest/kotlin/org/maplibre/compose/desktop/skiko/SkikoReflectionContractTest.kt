package org.maplibre.compose.desktop.skiko

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Compose Desktop and Skiko internals the default host reflects into, so that a Compose
 * upgrade that moves one fails here rather than as a blank map at runtime. Only existence is
 * asserted, so this runs headlessly on any platform whichever backend that platform uses.
 *
 * A failure means Compose or Skiko moved something and `SkikoReflection` needs updating to match.
 */
class SkikoReflectionContractTest {

  @Test
  fun `inherited field reads observe replacement values and subclass fields`() {
    val inherited = InheritedTarget()
    with(SkikoReflection) {
      assertEquals("initial", inherited.getField("context"))
      inherited.replaceContext("replacement")
      assertEquals("replacement", inherited.getField("context"))
      assertEquals("shadow", ShadowTarget().getField("context"))
      assertEquals("replacement", inherited.getField("context"))
    }
  }

  @Test
  fun `static invocation accepts the null result of a void method`() {
    StaticTarget.called = false
    assertNull(with(SkikoReflection) { StaticTarget::class.java.staticInvoke("record") })
    assertTrue(StaticTarget.called)
  }

  @Test
  fun `SkiaLayer exposes the redrawer and backing layer the host needs`() {
    val skiaLayer = Class.forName(SkikoReflection.SkiaLayerClass)
    assertMethod(skiaLayer, "getRedrawer\$skiko")
    assertMethod(skiaLayer, "getWindowHandle")
    assertField(skiaLayer, "backedLayer")
  }

  @Test
  fun `ComposeWindow exposes the panel the host walks to find the layer`() {
    assertField(Class.forName(SkikoReflection.ComposeWindowClass), "composePanel")
  }

  @Test
  fun `each redrawer exposes its context handler`() {
    for (redrawer in
      listOf(
        SkikoReflection.LinuxOpenGlRedrawerClass,
        SkikoReflection.MetalRedrawerClass,
        SkikoReflection.Direct3dRedrawerClass,
      )) {
      assertField(Class.forName(redrawer), "contextHandler")
    }
  }

  @Test
  fun `the Linux OpenGL redrawer exposes its native context`() {
    assertField(Class.forName(SkikoReflection.LinuxOpenGlRedrawerClass), "context")
  }

  @Test
  fun `the Direct3D redrawer exposes its device and context factory`() {
    assertField(Class.forName(SkikoReflection.Direct3dRedrawerClass), "device")
    assertMethod(Class.forName(SkikoReflection.Direct3dContextHandlerClass), "makeContext")
  }

  @Test
  fun `the Metal context handler exposes the device and context the host reads`() {
    assertField(Class.forName(SkikoReflection.MetalContextHandlerClass), "device")
    assertField(Class.forName(SkikoReflection.ContextHandlerClass), "context")
    assertMethod(Class.forName(SkikoReflection.ContextHandlerClass), "getContext")
    // Declared abstract on ContextHandler and implemented on ContextBasedContextHandler; the
    // lookup walks superclasses, so asserting on the base is enough.
    assertMethod(Class.forName(SkikoReflection.ContextHandlerClass), "initContext")
  }

  @Test
  fun `the Linux drawing surface helpers are callable`() {
    val helpers = Class.forName(SkikoReflection.AwtLinuxDrawingSurfaceHelpersClass)
    assertStaticMethod(helpers, "lockLinuxDrawingSurface", parameterCount = 1)
    assertStaticMethod(helpers, "unlockLinuxDrawingSurface", parameterCount = 1)

    // Skiko generates this synthetic accessor for an internal member; it is the only way to make
    // the window's GL context current from outside Skiko.
    assertStaticMethod(
      Class.forName(SkikoReflection.LinuxOpenGlRedrawerHelpersClass),
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
    val found =
      owner.methods.firstOrNull {
        it.name == name && it.parameterCount == parameterCount && Modifier.isStatic(it.modifiers)
      }
    assertNotNull(
      found,
      "${owner.name} no longer declares a static '$name' taking $parameterCount argument(s)",
    )
  }

  class StaticTarget {
    companion object {
      var called = false

      @JvmStatic
      fun record() {
        called = true
      }
    }
  }

  open class FieldTarget {
    private var context = "initial"

    fun replaceContext(value: String) {
      context = value
    }
  }

  class InheritedTarget : FieldTarget()

  class ShadowTarget : FieldTarget() {
    private val context = "shadow"
  }
}
