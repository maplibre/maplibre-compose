package org.maplibre.compose.desktop.bridge

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer

/**
 * Direct GLES 3 calls through ANGLE. LWJGL's desktop `GL.createCapabilities`
 * binds 1.1 entry points to `opengl32`/`wglGetProcAddress`, which are not the
 * current context on a Tao/ANGLE surface.
 */
internal object AngleGl {
  private val linker = Linker.nativeLinker()

  fun isUsable(): Boolean = address("glGetError") != 0L && address("glGenTextures") != 0L

  fun address(name: String): Long = AngleEglFunctionProvider.address(name)

  fun getError(): Int = invokeInt(glGetError)

  fun getInteger(pname: Int): Int =
    Arena.ofConfined().use { arena ->
      val out = arena.allocate(ValueLayout.JAVA_INT)
      invokeVoid(glGetIntegerv, pname, out)
      out.get(ValueLayout.JAVA_INT, 0)
    }

  fun getStringi(name: Int, index: Int): String? {
    val pointer = invokeAddress(glGetStringi, name, index)
    if (pointer.address() == 0L) return null
    return pointer.reinterpret(1024).getString(0)
  }

  fun genTextures(): Int =
    Arena.ofConfined().use { arena ->
      val out = arena.allocate(ValueLayout.JAVA_INT)
      invokeVoid(glGenTextures, 1, out)
      out.get(ValueLayout.JAVA_INT, 0)
    }

  fun deleteTextures(texture: Int) {
    if (texture == 0) return
    Arena.ofConfined().use { arena ->
      val names = arena.allocate(ValueLayout.JAVA_INT)
      names.set(ValueLayout.JAVA_INT, 0, texture)
      invokeVoid(glDeleteTextures, 1, names)
    }
  }

  fun bindTexture(target: Int, texture: Int) {
    invokeVoid(glBindTexture, target, texture)
  }

  fun texParameteri(target: Int, pname: Int, param: Int) {
    invokeVoid(glTexParameteri, target, pname, param)
  }

  fun texImage2D(
    target: Int,
    level: Int,
    internalFormat: Int,
    width: Int,
    height: Int,
    border: Int,
    format: Int,
    type: Int,
    pixels: ByteBuffer?,
  ) {
    invokeVoid(
      glTexImage2D,
      target,
      level,
      internalFormat,
      width,
      height,
      border,
      format,
      type,
      pixels.toSegment(),
    )
  }

  fun texSubImage2D(
    target: Int,
    level: Int,
    xOffset: Int,
    yOffset: Int,
    width: Int,
    height: Int,
    format: Int,
    type: Int,
    pixels: ByteBuffer,
  ) {
    invokeVoid(
      glTexSubImage2D,
      target,
      level,
      xOffset,
      yOffset,
      width,
      height,
      format,
      type,
      pixels.toSegment(),
    )
  }

  fun genFramebuffers(): Int =
    Arena.ofConfined().use { arena ->
      val out = arena.allocate(ValueLayout.JAVA_INT)
      invokeVoid(glGenFramebuffers, 1, out)
      out.get(ValueLayout.JAVA_INT, 0)
    }

  fun deleteFramebuffers(framebuffer: Int) {
    if (framebuffer == 0) return
    Arena.ofConfined().use { arena ->
      val names = arena.allocate(ValueLayout.JAVA_INT)
      names.set(ValueLayout.JAVA_INT, 0, framebuffer)
      invokeVoid(glDeleteFramebuffers, 1, names)
    }
  }

  fun bindFramebuffer(target: Int, framebuffer: Int) {
    invokeVoid(glBindFramebuffer, target, framebuffer)
  }

  fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) {
    invokeVoid(glFramebufferTexture2D, target, attachment, textarget, texture, level)
  }

  fun checkFramebufferStatus(target: Int): Int = invokeInt(glCheckFramebufferStatus, target)

  private val glGetError = bind("glGetError", FunctionDescriptor.of(ValueLayout.JAVA_INT))
  private val glGetIntegerv =
    bind("glGetIntegerv", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
  private val glGetStringi =
    bind(
      "glGetStringi",
      FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
  private val glGenTextures =
    bind("glGenTextures", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
  private val glDeleteTextures =
    bind("glDeleteTextures", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
  private val glBindTexture =
    bind("glBindTexture", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
  private val glTexParameteri =
    bind(
      "glTexParameteri",
      FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
  private val glTexImage2D =
    bind(
      "glTexImage2D",
      FunctionDescriptor.ofVoid(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
      ),
    )
  private val glTexSubImage2D =
    bind(
      "glTexSubImage2D",
      FunctionDescriptor.ofVoid(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
      ),
    )
  private val glGenFramebuffers =
    bind("glGenFramebuffers", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
  private val glDeleteFramebuffers =
    bind("glDeleteFramebuffers", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
  private val glBindFramebuffer =
    bind("glBindFramebuffer", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
  private val glFramebufferTexture2D =
    bind(
      "glFramebufferTexture2D",
      FunctionDescriptor.ofVoid(
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT,
      ),
    )
  private val glCheckFramebufferStatus =
    bind("glCheckFramebufferStatus", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))

  private fun bind(name: String, descriptor: FunctionDescriptor): Lazy<MethodHandle?> = lazy {
    val addr = address(name)
    if (addr == 0L) null else linker.downcallHandle(MemorySegment.ofAddress(addr), descriptor)
  }

  private fun invokeVoid(target: Lazy<MethodHandle?>, vararg args: Any?) {
    requireHandle(target).invokeWithArguments(*args)
  }

  private fun invokeInt(target: Lazy<MethodHandle?>, vararg args: Any?): Int =
    requireHandle(target).invokeWithArguments(*args) as Int

  private fun invokeAddress(target: Lazy<MethodHandle?>, vararg args: Any?): MemorySegment =
    requireHandle(target).invokeWithArguments(*args) as MemorySegment

  private fun requireHandle(target: Lazy<MethodHandle?>): MethodHandle =
    checkNotNull(target.value) { "ANGLE GL function is missing" }

  private fun ByteBuffer?.toSegment(): MemorySegment {
    if (this == null) return MemorySegment.NULL
    check(isDirect) { "ANGLE texture uploads need a direct ByteBuffer" }
    return MemorySegment.ofBuffer(this)
  }
}
