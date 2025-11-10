package org.maplibre.kmp.native.map

import java.awt.Canvas
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.HierarchyBoundsListener
import java.awt.event.HierarchyEvent
import javax.swing.SwingUtilities
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
    addHierarchyBoundsListener(ResizeHandler())
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

    // HACK: Force a repaint by resizing the window slightly to avoid a ghost map on macoOS.
    val root = SwingUtilities.getWindowAncestor(this)
    val oWidth = root.width
    val oHeight = root.height
    root.size = Dimension(oWidth + 1, oHeight + 1)
    root.size = Dimension(oWidth, oHeight)
  }

  /**
   * Handles resizing of the MapCanvas. When the component is resized, it updates the size of the
   * renderer and the map to match the new dimensions. The map size is adjusted based on logical
   * pixels, while the renderer size is adjusted based on physical pixels.
   */
  private class ResizeHandler : ComponentListener, HierarchyBoundsListener {

    override fun componentHidden(e: ComponentEvent) {}

    override fun componentShown(e: ComponentEvent) {}

    override fun componentMoved(e: ComponentEvent) {}

    override fun componentResized(e: ComponentEvent) {
      val canvas = e.component as? MapCanvas ?: return
      val pixelRatio = canvas.graphicsConfiguration.defaultTransform.scaleX.toFloat()
      val size = Size(width = canvas.width, height = canvas.height)
      canvas.renderer?.setSize(size * pixelRatio)
      canvas.map?.setSize(size)
    }

    override fun ancestorMoved(e: HierarchyEvent) {}

    override fun ancestorResized(e: HierarchyEvent) {
      val canvas = e.component as? MapCanvas ?: return

      // HACK: AWT does not correctly update position of the attached layer when:
      // - the component is at the top of the window
      // - the component is a fixed size
      // - the window is resized vertically
      // Because AWT treats the top-left as (0,0), while Apple treats the bottom-left as (0,0), so
      // AWT thinks the component hasn't moved, but the CALayer needs to be updated.
      val oX = canvas.location.x
      val oY = canvas.location.y
      canvas.setLocation(oX + 1, oY + 1)
      canvas.setLocation(oX, oY)
    }
  }
}
