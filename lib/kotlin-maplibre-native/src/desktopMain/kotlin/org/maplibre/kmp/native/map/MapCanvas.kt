package org.maplibre.kmp.native

import java.awt.Canvas
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer
import org.maplibre.kmp.native.map.ClientOptions
import org.maplibre.kmp.native.map.MapLibreMap
import org.maplibre.kmp.native.map.MapObserver
import org.maplibre.kmp.native.map.MapOptions
import org.maplibre.kmp.native.map.ResourceOptions
import org.maplibre.kmp.native.util.Size

/**
 * A Canvas that automatically initializes and manages a MapLibre map. This class encapsulates all
 * the initialization logic and provides a callback when the map is ready for configuration.
 */
public class MapCanvas(
  private val mapObserver: MapObserver,
  private val mapOptions: MapOptions,
  private val resourceOptions: ResourceOptions,
  private val clientOptions: ClientOptions,
  private val onMapReady: ((MapLibreMap, MapCanvas) -> Unit) = { _, _ -> },
) : Canvas() {

  private var map: MapLibreMap? = null
  private var renderTimer: Timer? = null

  init {
    addComponentListener(
      object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
          if (width > 0 && height > 0) {
            if (map == null) {
              initializeMap()
            } else {
              val pixelRatio = graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1.0f
              map?.setSize(
                Size(
                  width = (this@MapCanvas.width * pixelRatio).toInt(),
                  height = (this@MapCanvas.height * pixelRatio).toInt(),
                )
              )
              repaint()
            }
          }
        }
      }
    )
  }

  private fun initializeMap() {
    try {
      val pixelRatio = graphicsConfiguration.defaultTransform.scaleX
      val frameRate = graphicsConfiguration.device.displayMode.refreshRate

      // Should match the canvas size
      val adjustedMapOptions =
        mapOptions.copy(
          pixelRatio = pixelRatio.toFloat(),
          size = Size(width = (width * pixelRatio).toInt(), height = (height * pixelRatio).toInt()),
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

      startRenderLoop(frameRate)

      onMapReady(map, this)
    } catch (e: Exception) {
      println("Failed to initialize MapLibre: ${e.message}")
      e.printStackTrace()
    }
  }

  private fun startRenderLoop(frameRate: Int) {
    renderTimer = Timer(1000 / frameRate) { map?.tick() }.apply { start() }
  }

  private fun dispose() {
    renderTimer?.stop()
    renderTimer = null
    map = null
  }

  override fun removeNotify() {
    super.removeNotify()
    dispose()
  }

  override fun paint(g: Graphics) {}

  override fun update(g: Graphics) {
    paint(g)
  }
}
