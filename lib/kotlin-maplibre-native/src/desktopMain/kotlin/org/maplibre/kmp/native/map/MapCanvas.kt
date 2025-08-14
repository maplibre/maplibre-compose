package org.maplibre.kmp.native.map

import java.awt.Canvas
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer
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

  private var map: MapLibreMap? = null
  private var renderTimer: Timer? = null

  init {
    addComponentListener(
      object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
          if (width == 0 || height == 0) return // maplibre requires non-zero dimensions
          map?.setSize(Size(width = this@MapCanvas.width, height = this@MapCanvas.height))
        }
      }
    )
  }

  private fun initializeMap() {
    try {
      // Should match the canvas size
      val adjustedMapOptions =
        mapOptions.copy(
          pixelRatio = graphicsConfiguration.defaultTransform.scaleX.toFloat(),
          size = Size(width = width, height = height),
        )

      val map =
        MapLibreMap(
            canvas = this,
            observer = mapObserver,
            options = adjustedMapOptions,
            resourceOptions = resourceOptions,
            clientOptions = clientOptions,
          )
          .also { this.map = it }

      onMapReady(map, this)
    } catch (e: Exception) {
      println("Failed to initialize MapLibre: ${e.message}")
      e.printStackTrace()
    }
  }

  override fun addNotify() {
    super.addNotify()
    renderTimer =
      Timer(1000 / graphicsConfiguration.device.displayMode.refreshRate) {
        if (map == null && isShowing && width > 0 && height > 0) initializeMap()
        map?.tick()
      }
    renderTimer?.start()
  }

  override fun removeNotify() {
    super.removeNotify()
    renderTimer?.stop()
    renderTimer = null
    map = null
  }

  override fun paint(g: Graphics) {
    // called for lightweight components - no-op
  }

  override fun update(g: Graphics) {
    // called for heavyweight components - no-op for now
    // though we should probably move the render trigger logic here
    // and call repaint() on invalidation
  }
}
