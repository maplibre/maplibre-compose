package org.maplibre.compose.gljs

import kotlin.js.Promise
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.wasm.onWasmReady
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.maplibre.compose.browser.MapLibre
import web.gl.WebGL2RenderingContext
import web.html.HTMLCanvasElement

internal const val GPU_CANVAS_SIZE: Int = 256

internal class BrowserGpu(
  val canvas: HTMLCanvasElement,
  val gl: WebGL2RenderingContext,
  val skia: DirectContext,
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

  // Emscripten's registry, not canvas.getContext: skia addresses a context by the integer name only
  // this registers.
  val registry: dynamic = js("globalThis").GL
  val attributes =
    js(
      "({alpha:1,depth:1,stencil:8,antialias:0,premultipliedAlpha:1,preserveDrawingBuffer:0," +
        "preferLowPowerToHighPerformance:0,failIfMajorPerformanceCaveat:0," +
        "enableExtensionsByDefault:1,explicitSwapControl:0,renderViaOffscreenBackBuffer:0," +
        "majorVersion:2})"
    )
  val handle = registry.createContext(canvas, attributes)
  check(handle != null && handle != undefined && handle != 0) {
    "no WebGL2 context in this browser"
  }
  registry.makeContextCurrent(handle)

  MapLibre.configure(workerUrl = LOCAL_WORKER_URL)
  val skia = DirectContext.makeGL()
  val hostContext = checkNotNull(EmscriptenGl.currentContext())

  return BrowserGpu(canvas, hostContext.webGlContext, skia)
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
