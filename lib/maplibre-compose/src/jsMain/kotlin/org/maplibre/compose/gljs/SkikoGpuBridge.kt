package org.maplibre.compose.gljs

import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.DirectContext

private val browserWindow: dynamic = js("window")

private const val MAKE_GL_SYMBOL = "org_jetbrains_skia_DirectContext__1nMakeGL"
private const val RESET_SYMBOL = "org_jetbrains_skia_DirectContext__1nReset"

/** Every GL state bit, matching `DirectContext.resetGLAll`. */
private const val GL_STATES = 0xffff

/**
 * Reaches the `GrDirectContext` Compose renders with, by wrapping skiko's wasm context factory
 * before Compose builds its renderer. A second `DirectContext.makeGL()` corrupts Compose's
 * rendering, so the existing one has to be caught as it is created.
 *
 * TODO: retire this, and [org.maplibre.compose.browser.MapLibre.initialize] with it, once
 *   [JetBrains/skiko#1219](https://github.com/JetBrains/skiko/pull/1219) or an equivalent lands.
 */
internal object SkikoGpuBridge {
  private var contextPointer: dynamic = null

  /** Which mangled property this build keeps a managed object's native pointer in. */
  private var pointerField: String? = null

  private var managedPrototype: dynamic = null

  private var hookInstalled = false

  val isReady: Boolean
    get() = contextPointer != null && pointerField != null

  /**
   * Must run after skiko's wasm module has published its exports and before Compose builds its
   * renderer. Returns false when those exports were not there to wrap.
   */
  fun install(): Boolean {
    if (hookInstalled) return true
    val original = browserWindow[MAKE_GL_SYMBOL]
    if (original == null || original == undefined) return false
    browserWindow[MAKE_GL_SYMBOL] = {
      val pointer = original()
      contextPointer = pointer
      pointer
    }
    hookInstalled = true
    learnPointerField()
    return true
  }

  /**
   * A stand-in for Compose's [DirectContext], carrying its native pointer. Built on a real managed
   * object's prototype because an optimized build reads that pointer field directly where a
   * development build goes through its accessor. It frees nothing — the pointer belongs to Compose
   * — and no instance method may dispatch on it.
   */
  fun directContext(): DirectContext? {
    val pointer = contextPointer ?: return null
    val field = pointerField ?: return null
    val prototype = managedPrototype ?: return null
    val stand: dynamic = js("Object").create(prototype)
    stand[field] = pointer
    return stand.unsafeCast<DirectContext>()
  }

  /** `DirectContext.resetGLAll()`, through the export, since that method needs a real instance. */
  fun resetGlState() {
    val pointer = contextPointer ?: return
    val reset = browserWindow[RESET_SYMBOL]
    if (reset == null || reset == undefined) return
    reset(pointer, GL_STATES)
  }

  fun diagnostic(): String =
    when {
      !hookInstalled -> "skiko's exports were not on the page when MapLibre Compose initialized"
      contextPointer == null -> "Compose has not created its GPU context yet"
      pointerField == null -> "skiko's native pointer field could not be identified in this build"
      else -> "ready"
    }

  /**
   * A wrong guess hands Skia a bogus address, which corrupts rendering rather than failing
   * outright, so ambiguity leaves this unset.
   */
  private fun learnPointerField() {
    if (pointerField != null) return
    val first = probe()
    val second = probe()
    try {
      val a: dynamic = first
      val b: dynamic = second
      val names = js("Object").getOwnPropertyNames(a).unsafeCast<Array<String>>()
      val candidates = names.filter { name ->
        val value = a[name]
        jsTypeOf(value) == "number" && value != 0 && value != b[name]
      }
      if (candidates.size == 1) {
        pointerField = candidates.single()
        managedPrototype = js("Object").getPrototypeOf(a)
      }
    } finally {
      first.close()
      second.close()
    }
  }

  private fun probe(): BackendTexture =
    BackendTexture.makeGL(
      width = 1,
      height = 1,
      isMipmapped = false,
      textureId = 0,
      textureTarget = GL_TEXTURE_2D,
      textureFormat = GL_RGBA8,
    )
}
