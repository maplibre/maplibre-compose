package org.maplibre.compose.gljs

import kotlin.js.Promise
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
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

internal fun gpuTest(block: suspend (BrowserGpu) -> Unit): Promise<*> =
  MainScope().promise { block(browserGpu()) }

/**
 * A distinct object that still forwards every WebGL call to [gl]. A resize can replace the context
 * identity without changing the underlying GPU, and tests use this stand-in for that.
 */
internal fun aliasedWebGlContext(gl: dynamic): dynamic =
  js(
      """
      (function(gl) {
        return new Proxy(gl, {
          get: function(target, prop) {
            var value = target[prop];
            return typeof value === 'function' ? value.bind(target) : value;
          }
        });
      })
      """
    )
    .unsafeCast<(dynamic) -> dynamic>()(gl)

private val emscriptenContextAttributes: dynamic =
  js(
    "({alpha:1,depth:1,stencil:8,antialias:0,premultipliedAlpha:1,preserveDrawingBuffer:0," +
      "preferLowPowerToHighPerformance:0,failIfMajorPerformanceCaveat:0," +
      "enableExtensionsByDefault:1,explicitSwapControl:0,renderViaOffscreenBackBuffer:0," +
      "majorVersion:2})"
  )

private val emscriptenRegistry: dynamic
  get() = js("globalThis").GL

/**
 * Registers one more Emscripten context, the leftover a resize leaves in `GL.contexts`, then
 * restores the suite's current context.
 */
internal fun withExtraEmscriptenContext(
  connected: Boolean = false,
  block: (HTMLCanvasElement) -> Unit,
) {
  val registry = emscriptenRegistry
  val previous = registry.currentContext?.handle
  val canvas = newCanvas(8)
  if (connected) document.body.asDynamic().appendChild(canvas)
  val handle = createEmscriptenContext(canvas)
  fun restore() {
    if (previous != null && previous != undefined) registry.makeContextCurrent(previous)
  }
  try {
    restore()
    block(canvas)
  } finally {
    registry.deleteContext(handle)
    canvas.remove()
    restore()
  }
}

private fun newCanvas(size: Int): HTMLCanvasElement {
  val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
  canvas.width = size
  canvas.height = size
  return canvas
}

private fun createEmscriptenContext(canvas: HTMLCanvasElement): dynamic {
  val handle = emscriptenRegistry.createContext(canvas, emscriptenContextAttributes)
  check(handle != null && handle != undefined && handle != 0) {
    "no WebGL2 context in this browser"
  }
  return handle
}

private fun createGpu(): BrowserGpu {
  val canvas = newCanvas(GPU_CANVAS_SIZE)

  // Emscripten's registry, not canvas.getContext: skia addresses a context by the integer name only
  // this registers.
  val handle = createEmscriptenContext(canvas)
  emscriptenRegistry.makeContextCurrent(handle)

  // The hook has to be installed before the context is made.
  MapLibre.configure(workerUrl = LOCAL_WORKER_URL)
  val skia = DirectContext.makeGL()
  check(SkikoGpuBridge.isReady) { SkikoGpuBridge.diagnostic() }

  return BrowserGpu(canvas, checkNotNull(EmscriptenGl.contextOf(canvas)), skia)
}

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
