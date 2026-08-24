package org.maplibre.compose.desktop.bridge

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * The slice of EGL this host needs to wrap an `ID3D11Texture2D` as a GL texture via
 * `EGL_ANGLE_d3d_texture_client_buffer`.
 */
internal object AngleEgl {
  const val EGL_NONE = 0x3038
  const val EGL_DRAW = 0x3059
  const val EGL_READ = 0x305A
  const val EGL_BACK_BUFFER = 0x3084
  const val EGL_TEXTURE_2D = 0x305F
  const val EGL_TEXTURE_RGBA = 0x305E
  const val EGL_TEXTURE_FORMAT = 0x3080
  const val EGL_TEXTURE_TARGET = 0x3081
  const val EGL_CONFIG_ID = 0x3028
  const val EGL_DEVICE_EXT = 0x322C
  const val EGL_D3D11_DEVICE_ANGLE = 0x33A1
  const val EGL_D3D_TEXTURE_ANGLE = 0x33A3
  const val EGL_TRUE = 1
  const val GL_TEXTURE_BINDING_2D = 0x8069

  private val linker = Linker.nativeLinker()

  fun currentDisplay(): Long = invokeAddress(eglGetCurrentDisplay).address()

  fun currentContext(): Long = invokeAddress(eglGetCurrentContext).address()

  fun currentSurface(which: Int): Long = invokeAddress(eglGetCurrentSurface, which).address()

  fun angleD3d11Device(): Long {
    val display = currentDisplay()
    check(display != 0L) { "No EGL display is current" }
    Arena.ofConfined().use { arena ->
      val deviceOut = arena.allocate(ValueLayout.ADDRESS)
      checkEgl(
        invokeInt(eglQueryDisplayAttribEXT, address(display), EGL_DEVICE_EXT, deviceOut) ==
          EGL_TRUE,
        "eglQueryDisplayAttribEXT(EGL_DEVICE_EXT)",
      )
      val device = deviceOut.get(ValueLayout.ADDRESS, 0).address()
      check(device != 0L) { "EGL display has no EGL_DEVICE_EXT" }
      val d3dOut = arena.allocate(ValueLayout.ADDRESS)
      checkEgl(
        invokeInt(
          eglQueryDeviceAttribEXT,
          address(device),
          EGL_D3D11_DEVICE_ANGLE,
          d3dOut,
        ) == EGL_TRUE,
        "eglQueryDeviceAttribEXT(EGL_D3D11_DEVICE_ANGLE)",
      )
      val d3d = d3dOut.get(ValueLayout.ADDRESS, 0).address()
      check(d3d != 0L) { "ANGLE EGL device has no ID3D11Device" }
      return d3d
    }
  }

  fun currentConfig(): Long {
    val display = currentDisplay()
    val context = currentContext()
    check(display != 0L && context != 0L) { "No EGL context is current" }
    Arena.ofConfined().use { arena ->
      val configIdOut = arena.allocate(ValueLayout.JAVA_INT)
      checkEgl(
        invokeInt(
          eglQueryContext,
          address(display),
          address(context),
          EGL_CONFIG_ID,
          configIdOut,
        ) == EGL_TRUE,
        "eglQueryContext(EGL_CONFIG_ID)",
      )
      val attribs = arena.allocate(ValueLayout.JAVA_INT, 3)
      attribs.setAtIndex(ValueLayout.JAVA_INT, 0, EGL_CONFIG_ID)
      attribs.setAtIndex(ValueLayout.JAVA_INT, 1, configIdOut.get(ValueLayout.JAVA_INT, 0))
      attribs.setAtIndex(ValueLayout.JAVA_INT, 2, EGL_NONE)
      val configOut = arena.allocate(ValueLayout.ADDRESS)
      val countOut = arena.allocate(ValueLayout.JAVA_INT)
      checkEgl(
        invokeInt(
          eglChooseConfig,
          address(display),
          attribs,
          configOut,
          1,
          countOut,
        ) == EGL_TRUE && countOut.get(ValueLayout.JAVA_INT, 0) > 0,
        "eglChooseConfig(EGL_CONFIG_ID)",
      )
      return configOut.get(ValueLayout.ADDRESS, 0).address()
    }
  }

  /**
   * Wraps [d3dTexture] (must belong to ANGLE's D3D11 device) as a `GL_TEXTURE_2D`. Restores the
   * previously current draw surface before returning.
   */
  fun bindD3dTexture(d3dTexture: Long): AngleBoundD3dTexture {
    val display = currentDisplay()
    val context = currentContext()
    val config = currentConfig()
    val hostDraw = currentSurface(EGL_DRAW)
    val hostRead = currentSurface(EGL_READ)
    check(display != 0L && context != 0L && config != 0L) { "ANGLE EGL trio is incomplete" }
    Arena.ofConfined().use { arena ->
      val attribs = arena.allocate(ValueLayout.JAVA_INT, 5)
      attribs.setAtIndex(ValueLayout.JAVA_INT, 0, EGL_TEXTURE_FORMAT)
      attribs.setAtIndex(ValueLayout.JAVA_INT, 1, EGL_TEXTURE_RGBA)
      attribs.setAtIndex(ValueLayout.JAVA_INT, 2, EGL_TEXTURE_TARGET)
      attribs.setAtIndex(ValueLayout.JAVA_INT, 3, EGL_TEXTURE_2D)
      attribs.setAtIndex(ValueLayout.JAVA_INT, 4, EGL_NONE)
      val pbuffer =
        invokeAddress(
            eglCreatePbufferFromClientBuffer,
            address(display),
            EGL_D3D_TEXTURE_ANGLE,
            address(d3dTexture),
            address(config),
            attribs,
          )
          .address()
      check(pbuffer != 0L) {
        "eglCreatePbufferFromClientBuffer failed: 0x${eglError().toString(16)}"
      }
      try {
        checkEgl(
          invokeInt(
            eglMakeCurrent,
            address(display),
            address(pbuffer),
            address(pbuffer),
            address(context),
          ) == EGL_TRUE,
          "eglMakeCurrent(pbuffer)",
        )
        val previous = AngleGl.getInteger(GL_TEXTURE_BINDING_2D)
        val textureName = AngleGl.genTextures()
        try {
          AngleGl.bindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, textureName)
          AngleGl.texParameteri(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
            org.lwjgl.opengl.GL11.GL_LINEAR,
          )
          AngleGl.texParameteri(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
            org.lwjgl.opengl.GL11.GL_LINEAR,
          )
          AngleGl.texParameteri(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S,
            org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE,
          )
          AngleGl.texParameteri(
            org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
            org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T,
            org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE,
          )
          checkEgl(
            invokeInt(eglBindTexImage, address(display), address(pbuffer), EGL_BACK_BUFFER) ==
              EGL_TRUE,
            "eglBindTexImage",
          )
          AngleGl.bindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previous)
          restoreCurrent(display, hostDraw, hostRead, context)
          return AngleBoundD3dTexture(textureName, display, pbuffer)
        } catch (error: Throwable) {
          if (error is VirtualMachineError) throw error
          AngleGl.deleteTextures(textureName)
          throw error
        }
      } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        restoreCurrent(display, hostDraw, hostRead, context)
        invokeInt(eglDestroySurface, address(display), address(pbuffer))
        throw error
      }
    }
  }

  fun release(bound: AngleBoundD3dTexture) {
    if (bound.display != 0L && bound.pbuffer != 0L) {
      runCatching {
        invokeInt(
          eglReleaseTexImage,
          address(bound.display),
          address(bound.pbuffer),
          EGL_BACK_BUFFER,
        )
      }
      runCatching { invokeInt(eglDestroySurface, address(bound.display), address(bound.pbuffer)) }
    }
    if (bound.textureName != 0 && AngleGl.isUsable()) {
      runCatching { AngleGl.deleteTextures(bound.textureName) }
    }
  }

  private fun restoreCurrent(display: Long, draw: Long, read: Long, context: Long) {
    if (draw != 0L && context != 0L) {
      invokeInt(eglMakeCurrent, address(display), address(draw), address(read), address(context))
    } else {
      invokeInt(eglMakeCurrent, address(display), address(0), address(0), address(0))
    }
  }

  private fun eglError(): Int = invokeInt(eglGetError)

  private fun checkEgl(ok: Boolean, operation: String) {
    check(ok) { "$operation failed with EGL error 0x${eglError().toString(16)}" }
  }

  private val eglGetCurrentDisplay =
    bind("eglGetCurrentDisplay", FunctionDescriptor.of(ValueLayout.ADDRESS))
  private val eglGetCurrentContext =
    bind("eglGetCurrentContext", FunctionDescriptor.of(ValueLayout.ADDRESS))
  private val eglGetCurrentSurface =
    bind("eglGetCurrentSurface", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT))
  private val eglGetError = bind("eglGetError", FunctionDescriptor.of(ValueLayout.JAVA_INT))
  private val eglQueryDisplayAttribEXT =
    bind(
      "eglQueryDisplayAttribEXT",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
      ),
    )
  private val eglQueryDeviceAttribEXT =
    bind(
      "eglQueryDeviceAttribEXT",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
      ),
    )
  private val eglQueryContext =
    bind(
      "eglQueryContext",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
      ),
    )
  private val eglChooseConfig =
    bind(
      "eglChooseConfig",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
      ),
    )
  private val eglCreatePbufferFromClientBuffer =
    bind(
      "eglCreatePbufferFromClientBuffer",
      FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
      ),
    )
  private val eglBindTexImage =
    bind(
      "eglBindTexImage",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
      ),
    )
  private val eglReleaseTexImage =
    bind(
      "eglReleaseTexImage",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT,
      ),
    )
  private val eglDestroySurface =
    bind(
      "eglDestroySurface",
      FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
  private val eglMakeCurrent =
    bind(
      "eglMakeCurrent",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
      ),
    )

  private fun bind(name: String, descriptor: FunctionDescriptor): Lazy<MethodHandle?> = lazy {
    val addr = AngleEglFunctionProvider.address(name)
    if (addr == 0L) null else linker.downcallHandle(MemorySegment.ofAddress(addr), descriptor)
  }

  private fun invokeInt(target: Lazy<MethodHandle?>, vararg args: Any?): Int =
    requireHandle(target).invokeWithArguments(*args) as Int

  private fun invokeAddress(target: Lazy<MethodHandle?>, vararg args: Any?): MemorySegment =
    requireHandle(target).invokeWithArguments(*args) as MemorySegment

  private fun requireHandle(target: Lazy<MethodHandle?>): MethodHandle =
    checkNotNull(target.value) { "ANGLE EGL function is missing" }

  private fun address(value: Long): MemorySegment = MemorySegment.ofAddress(value)
}

/** A GL texture whose storage is an ANGLE pbuffer wrapping a D3D11 texture. */
internal class AngleBoundD3dTexture(
  val textureName: Int,
  val display: Long,
  val pbuffer: Long,
) : AutoCloseable {
  override fun close() {
    AngleEgl.release(this)
  }
}
