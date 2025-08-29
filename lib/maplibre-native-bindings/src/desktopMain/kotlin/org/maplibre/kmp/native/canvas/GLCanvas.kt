package org.maplibre.kmp.native.canvas

import java.awt.Canvas
import java.awt.Graphics

public abstract class GLCanvas : Canvas() {

  private var jawtContext: JawtContext? = null
  private var glContext: GLContext? = null

  protected abstract fun paintGL()

  override fun paint(g: Graphics) {
    val jawtContext = this.jawtContext ?: JawtContext.create(this).also { this.jawtContext = it }
    val glContext = this.glContext ?: GLContext.create(jawtContext).also { this.glContext = it }
    jawtContext.lock()
    try {
      glContext.bind()
      glContext.activate()
      paintGL()
      glContext.swap()
      glContext.deactivate()
    } finally {
      jawtContext.unlock()
    }
  }

  override fun update(g: Graphics) {
    paint(g)
  }

  override fun removeNotify() {
    super.removeNotify()
    glContext?.dispose()
    jawtContext?.dispose()
  }
}
