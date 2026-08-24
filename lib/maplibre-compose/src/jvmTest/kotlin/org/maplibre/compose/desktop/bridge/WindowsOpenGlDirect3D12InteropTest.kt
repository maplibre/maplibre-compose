package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30.glBindFramebuffer
import org.lwjgl.opengl.GL30.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30.glDeleteFramebuffers
import org.lwjgl.opengl.GL30.glFramebufferTexture2D
import org.lwjgl.opengl.GL30.glGenFramebuffers
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.ProductionBridgeTestRenderDriver
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

class WindowsOpenGlDirect3D12InteropTest {

  @Test
  fun `initial import and repeated acquisition reuse one shared texture`() = onWindowsOpenGl {
    ProductionBridgeTestRenderDriver.create().use { host ->
      val first = host.acquire(1, FIRST_EXTENT)
      val second = host.acquire(2, FIRST_EXTENT)
      val firstTarget = assertIs<OpenGlTextureTarget>(first.target)
      val secondTarget = assertIs<OpenGlTextureTarget>(second.target)

      assertEquals(firstTarget.generation, secondTarget.generation)
      assertEquals(firstTarget.textureName, secondTarget.textureName)

      host.releaseFrame(first)
      host.releaseFrame(second)
    }
  }

  @Test
  fun `repeated rendering into one imported target presents new pixels`() = onWindowsOpenGl {
    ProductionBridgeTestRenderDriver.create().use { host ->
      val first = host.acquire(1, FIRST_EXTENT)
      val firstTarget = assertIs<OpenGlTextureTarget>(first.target)
      host.clear(firstTarget, FIRST_PIXEL)
      assertTrue(host.present(firstTarget))
      assertNear(FIRST_PIXEL, host.readPixel(0, 0), "first shared-target contents")

      val second = host.acquire(2, FIRST_EXTENT)
      val secondTarget = assertIs<OpenGlTextureTarget>(second.target)
      assertEquals(firstTarget.textureName, secondTarget.textureName)
      host.clear(secondTarget, SECOND_PIXEL)
      assertTrue(host.present(secondTarget))
      assertNear(SECOND_PIXEL, host.readPixel(0, 0), "reused shared-target contents")

      host.releaseFrame(first)
      host.releaseFrame(second)
    }
  }

  @Test
  fun `a resize can still present the retired generation`() = onWindowsOpenGl {
    ProductionBridgeTestRenderDriver.create().use { host ->
      val first = host.acquire(1, FIRST_EXTENT)
      val firstTarget = assertIs<OpenGlTextureTarget>(first.target)
      host.clear(firstTarget, FIRST_PIXEL)
      assertTrue(host.present(firstTarget))

      val second = host.acquire(2, SECOND_EXTENT)
      val secondTarget = assertIs<OpenGlTextureTarget>(second.target)
      host.clear(secondTarget, SECOND_PIXEL)

      assertTrue(host.present(firstTarget))
      assertNear(FIRST_PIXEL, host.readPixel(0, 0), "retired generation after resize")
      assertTrue(host.present(secondTarget))
      assertNear(SECOND_PIXEL, host.readPixel(0, 0), "current generation after resize")

      host.releaseFrame(first)
      host.releaseFrame(second)
    }
  }

  @Test
  fun `an inherited GL error does not poison a replacement memory import`() = onWindowsOpenGl {
    ProductionBridgeTestRenderDriver.create().use { host ->
      val first = host.acquire(1, FIRST_EXTENT)
      host.withRendererAccess {
        clearGlErrors()
        glEnable(Int.MIN_VALUE)
      }

      val replacement = host.acquire(2, SECOND_EXTENT)

      assertNotEquals(first.target.generation, replacement.target.generation)
      host.releaseFrame(first)
      host.releaseFrame(replacement)
    }
  }

  private fun ProductionBridgeTestRenderDriver.acquire(
    frameId: Long,
    extent: MapExtent,
  ): MlnFfiMapFrame =
    assertIs<MlnFfiMapFrameAcquisition.Acquired>(acquireFrame(frameId, extent, null)).frame

  private fun ProductionBridgeTestRenderDriver.clear(
    target: OpenGlTextureTarget,
    color: RgbaPixel,
  ) {
    withRendererAccess {
      target.makeContextCurrent()
      val framebuffer = glGenFramebuffers()
      try {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(
          GL_FRAMEBUFFER,
          GL_COLOR_ATTACHMENT0,
          GL_TEXTURE_2D,
          target.textureName,
          0,
        )
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER))
        glViewport(0, 0, target.extent.physicalWidth, target.extent.physicalHeight)
        glClearColor(
          color.red / 255f,
          color.green / 255f,
          color.blue / 255f,
          color.alpha / 255f,
        )
        glClear(GL_COLOR_BUFFER_BIT)
        glFinish()
      } finally {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glDeleteFramebuffers(framebuffer)
      }
    }
  }

  private inline fun onWindowsOpenGl(block: () -> Unit) {
    assumeTrue(
      "the WGL-to-Direct3D 12 bridge exists only on Windows",
      System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    )
    assumeTrue(
      "the WGL-to-Direct3D 12 test needs the OpenGL runtime packaged",
      packagedRuntime() == RenderBackend.OPENGL,
    )
    block()
  }

  private fun packagedRuntime(): RenderBackend? = runCatching {
    Maplibre.loadNativeLibrary()
    Maplibre.supportedRenderBackends().singleOrNull()
  }
    .getOrNull()

  private fun assertNear(expected: RgbaPixel, actual: RgbaPixel, label: String) {
    val tolerance = 2
    assertTrue(
      kotlin.math.abs(expected.red - actual.red) <= tolerance &&
        kotlin.math.abs(expected.green - actual.green) <= tolerance &&
        kotlin.math.abs(expected.blue - actual.blue) <= tolerance &&
        kotlin.math.abs(expected.alpha - actual.alpha) <= tolerance,
      "$label: expected $expected within $tolerance per channel, got $actual",
    )
  }

  private companion object {
    val FIRST_EXTENT =
      MapExtent.fromPhysical(physicalWidth = 64, physicalHeight = 64, scaleFactor = 1.0)
    val SECOND_EXTENT =
      MapExtent.fromPhysical(physicalWidth = 96, physicalHeight = 80, scaleFactor = 1.0)
    val FIRST_PIXEL = RgbaPixel(0x19, 0x7f, 0xe5, 0xff)
    val SECOND_PIXEL = RgbaPixel(0xd9, 0x52, 0x36, 0xff)
  }
}
