package org.maplibre.compose.map

import kotlin.time.Duration
import kotlin.time.TimeSource
import org.maplibre.compose.gljs.GlJsFrameTarget
import org.maplibre.compose.gljs.GlJsSurfaceSession
import web.animations.cancelAnimationFrame
import web.animations.requestAnimationFrame
import web.cssom.matchMedia
import web.dom.document
import web.events.EventHandler
import web.html.HTMLElement
import web.resize.ResizeObserver
import web.timers.clearTimeout
import web.timers.setTimeout

/** Owns the DOM and frame requests, independently of the framework hosting the container. */
internal class WebMapSurface(
  private val onDensityChanged: (Float) -> Unit,
  private val onFailure: (Throwable) -> Unit,
) : GlJsSurfaceSession, AutoCloseable {
  val element = document.createElement("div").unsafeCast<HTMLElement>()
  private var renderer: GlJsMapSession? = null
  private var frame: web.animations.FrameRequestId? = null
  private var timer: web.timers.Timeout? = null
  private val pacer = MapFramePacer(followsFrameClock = true)
  private var closed = false
  private var host: HTMLElement? = null
  private var extent = MapExtent.fromLogical(1, 1, browserPixelRatio())
  private var hasSize = false
  private var presentFrames = false
  private var hasPresentedFrame = false
  private val observer = ResizeObserver { _, _ -> resize() }
  private var resolution = matchMedia(resolutionQuery(browserPixelRatio()))

  var isActive: Boolean = true
    set(value) {
      field = value
      if (value) requestFrame() else cancelFrame()
    }

  init {
    element.style.overflow = "hidden"
    park()
    resolution.onchange = EventHandler { updateResolution() }
  }

  fun connect(renderer: GlJsMapSession) {
    this.renderer = renderer
    renderer.onSurfaceAvailable(this)
  }

  fun attach(container: HTMLElement) {
    observer.disconnect()
    host = container
    container.appendChild(element)
    element.style.position = "relative"
    element.style.left = "0"
    element.style.top = "0"
    observer.observe(container)
    resize()
  }

  fun detach() {
    observer.disconnect()
    host = null
    hasSize = false
    park()
    updateVisibility()
  }

  fun showFrames(value: Boolean) {
    if (presentFrames == value) return
    presentFrames = value
    if (!value) hasPresentedFrame = false
    updateVisibility()
    requestFrame()
  }

  private fun park() {
    // Keep the engine and its viewport alive between attachments. GL JS reads container layout
    // even when no host is showing the map, so display:none would give it the wrong dimensions.
    element.style.position = "fixed"
    element.style.left = "-100000px"
    element.style.top = "0"
    element.style.visibility = "hidden"
    document.body.appendChild(element)
  }

  private fun resize() {
    if (closed) return
    val host = host
    hasSize = host != null && host.clientWidth > 0 && host.clientHeight > 0
    val scale = browserPixelRatio()
    extent =
      if (host != null && hasSize) {
        MapExtent.fromLogical(host.clientWidth, host.clientHeight, scale)
      } else {
        MapExtent.fromLogical(extent.width, extent.height, scale)
      }
    onDensityChanged(extent.scaleFactor.toFloat())
    updateVisibility()
    requestFrame()
  }

  private fun updateResolution() {
    resolution.onchange = null
    resolution = matchMedia(resolutionQuery(browserPixelRatio()))
    resolution.onchange = EventHandler { updateResolution() }
    resize()
  }

  private fun updateVisibility() {
    element.style.visibility = if (hasSize && hasPresentedFrame) "inherit" else "hidden"
  }

  override fun requestFrame() {
    if (closed || !isActive || frame != null || renderer == null) return
    timer?.let(::clearTimeout)
    timer = null
    val remaining = pacer.remaining(renderer?.maximumFps)
    if (remaining > Duration.ZERO) {
      timer =
        setTimeout(
          {
            timer = null
            requestFrame()
          },
          ((remaining.inWholeNanoseconds + 999_999) / 1_000_000)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
        )
      return
    }
    frame = requestAnimationFrame {
      frame = null
      if (!closed && isActive) {
        try {
          if (pacer.remaining(renderer?.maximumFps) > Duration.ZERO) {
            requestFrame()
            return@requestAnimationFrame
          }
          val start = TimeSource.Monotonic.markNow()
          if (renderer?.render(GlJsFrameTarget.OwnCanvas, extent) == true) {
            pacer.rendered(start)
            if (presentFrames) {
              hasPresentedFrame = true
              updateVisibility()
            }
          }
        } catch (error: Throwable) {
          onFailure(error)
        }
      }
    }
  }

  private fun cancelFrame() {
    timer?.let(::clearTimeout)
    timer = null
    frame?.let(::cancelAnimationFrame)
    frame = null
  }

  override fun close() {
    if (closed) return
    closed = true
    cancelFrame()
    observer.disconnect()
    resolution.onchange = null
    renderer = null
    host = null
    element.remove()
  }
}

internal fun browserPixelRatio(): Double = js("window.devicePixelRatio")

private fun resolutionQuery(density: Double): web.cssom.MediaQuery =
  js("'(resolution: ' + density + 'dppx)'")
