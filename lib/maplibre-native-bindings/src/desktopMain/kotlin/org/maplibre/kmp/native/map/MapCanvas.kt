package org.maplibre.kmp.native.map

import java.awt.Canvas
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import org.maplibre.kmp.native.util.Size

/**
 * A Canvas that automatically initializes and manages a MapLibre map. This class encapsulates all
 * the initialization logic and provides a callback when the map is ready for configuration.
 */
public class MapCanvas(
  private val mapObserver: MapObserver,
  private val mapOptions: MapOptions = MapOptions(),
  private val resourceOptions: ResourceOptions = ResourceOptions(),
  private val clientOptions: ClientOptions = ClientOptions(),
  private val onMapReady: ((MapLibreMap, MapCanvas) -> Unit) = { _, _ -> },
) : Canvas() {

  private var renderer: CanvasRenderer? = null
  private var map: MapLibreMap? = null

  override fun paint(g: Graphics) {
    renderer?.render()
  }

  override fun update(g: Graphics) {
    paint(g)
  }

  init {
    addComponentListener(ResizeHandler())
  }

  override fun addNotify() {
    super.addNotify()

    // Initialize a map
    val pixelRatio = this.graphicsConfiguration.defaultTransform.scaleX.toFloat()
    val map =
      MapLibreMap(
        frontend = CanvasRenderer(this, pixelRatio).also { renderer = it },
        observer = mapObserver,
        options = mapOptions.copy(pixelRatio = pixelRatio),
        resourceOptions = resourceOptions,
        clientOptions = clientOptions,
      )
    this.map = map

    // HACK: Force a re-layout so AWT sets the correct position and size on macOS.
    // For more info, see the "quirks" in:
    // https://github.com/sgothel/jogl/blob/367f3a875096b0091ba6c1053d2804a252062130/src/nativewindow/classes/jogamp/nativewindow/jawt/JAWTUtil.java
    val originalWidth = this.width
    val originalHeight = this.height
    this.size = Dimension(originalWidth + 1, originalHeight + 1)
    this.size = Dimension(originalWidth, originalHeight)
    onMapReady(map, this)
    repaint()
  }

  override fun removeNotify() {
    super.removeNotify()
    map?.dispose()
    map = null
    renderer?.dispose()
    renderer = null
  }

  /**
   * Handles resizing of the MapCanvas. When the component is resized, it updates the size of the
   * renderer and the map to match the new dimensions. The map size is adjusted based on logical
   * pixels, while the renderer size is adjusted based on physical pixels.
   */
  private class ResizeHandler : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent) {
      val canvas = e.component as? MapCanvas ?: return
      val pixelRatio = canvas.graphicsConfiguration.defaultTransform.scaleX.toFloat()
      val size = Size(width = canvas.width, height = canvas.height)
      canvas.renderer?.setSize(size * pixelRatio)
      canvas.map?.setSize(size)
    }
  }
}
