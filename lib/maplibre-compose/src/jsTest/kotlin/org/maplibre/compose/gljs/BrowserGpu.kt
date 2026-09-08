package org.maplibre.compose.gljs

import kotlin.js.Promise
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.jetbrains.skiko.wasm.onWasmReady
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.maplibre.compose.browser.installMapLibreCompose
import web.gl.WebGL2RenderingContext
import web.html.HTMLCanvasElement

internal const val GPU_CANVAS_SIZE: Int = 256

internal class BrowserGpu(
  val canvas: HTMLCanvasElement,
  val gl: WebGL2RenderingContext,
)

/** Stood up at most once per Karma run; neither context is ever closed. */
private val gpu: Promise<BrowserGpu> by lazy {
  Promise { resolve, reject ->
    onWasmReady {
      try {
        resolve(createGpu())
      } catch (error: Throwable) {
        reject(error)
      }
    }
  }
}

internal suspend fun browserGpu(): BrowserGpu = gpu.await()

private fun createGpu(): BrowserGpu {
  val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
  canvas.width = GPU_CANVAS_SIZE
  canvas.height = GPU_CANVAS_SIZE

  installMapLibreCompose(workerUrl = LOCAL_WORKER_URL)
  val gl =
    checkNotNull(canvas.asDynamic().getContext("webgl2")).unsafeCast<WebGL2RenderingContext>()
  return BrowserGpu(canvas, gl)
}

internal fun browserRenderTarget(
  gpu: BrowserGpu,
  width: Int,
  height: Int,
): TestGlJsRenderTarget =
  TestGlJsRenderTarget(
    gpu = gpu,
    widthPx = width,
    heightPx = height,
  )

/** Reads [framebuffer] — null meaning the canvas itself — as tightly packed RGBA bytes. */
internal fun readFramebuffer(gl: dynamic, framebuffer: Any?, width: Int, height: Int): ByteArray {
  val pixels = Uint8Array(width * height * 4)
  gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer)
  gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
  val error = gl.getError().unsafeCast<Int>()
  gl.bindFramebuffer(gl.FRAMEBUFFER, null)
  check(error == 0) { "glReadPixels failed with 0x${error.toString(16)}" }
  return ByteArray(width * height * 4) { pixels[it] }
}

/** Every colour in [rgba] and how many pixels carry it. */
internal fun histogram(rgba: ByteArray): Map<String, Int> {
  val counts = HashMap<Int, Int>()
  var index = 0
  while (index < rgba.size) {
    val rgb =
      ((rgba[index].toInt() and 0xff) shl 16) or
        ((rgba[index + 1].toInt() and 0xff) shl 8) or
        (rgba[index + 2].toInt() and 0xff)
    counts[rgb] = (counts[rgb] ?: 0) + 1
    index += 4
  }
  return counts.entries.associate { (rgb, count) ->
    "#${rgb.toString(16).padStart(6, '0')}" to count
  }
}
