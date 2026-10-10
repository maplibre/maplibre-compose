package org.maplibre.compose.layers

import js.objects.unsafeJso
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.gljs.CustomLayerInterface
import org.maplibre.compose.gljs.CustomProjectionData
import org.maplibre.compose.gljs.CustomProjectionOptions
import org.maplibre.compose.gljs.CustomRenderMethodInput
import org.maplibre.compose.gljs.CustomShaderData
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.LngLat
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.StyleImageData
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.spatialk.geojson.Position

internal class IndicatorImage(val pixels: StyleImageData, val pixelRatio: Double)

/** Owns the custom layer's measurements, textures and last rendered interactive geometry. */
internal class GlJsLocationIndicator(
  initial: JsonObject,
  private val map: MaplibreMap,
  private val image: (String) -> IndicatorImage?,
) {
  var definition = initial
    private set

  private val id = initial.getValue("id").jsonPrimitive.content

  private fun property(name: String): JsonElement? =
    definition[name]
      ?: definition["paint"]?.jsonObject?.get(name)
      ?: definition["layout"]?.jsonObject?.get(name)

  private fun number(name: String, fallback: Double = 0.0) =
    property(name)?.jsonPrimitive?.doubleOrNull ?: fallback

  private fun text(name: String) = (property(name) as? JsonPrimitive)?.contentOrNull

  // Decode at the style boundary, not in the render loop.
  private class ImageLayer(val id: String?, val size: Double, val shift: Int)

  private inner class Appearance {
    val visible = text("visibility") != "none"
    val minZoom = number("minzoom")
    val maxZoom = number("maxzoom", 24.0)
    val compensation = number("perspective-compensation", 0.85)
    val displacement = number("image-tilt-displacement")
    val fill = text("accuracy-radius-color") ?: "rgba(0,0,255,0.15)"
    val border = text("accuracy-radius-border-color") ?: "blue"
    val images =
      listOf(
        ImageLayer(text("shadow-image"), number("shadow-image-size", 1.0), -1),
        ImageLayer(text("bearing-image"), number("bearing-image-size", 1.0), 0),
        ImageLayer(text("top-image"), number("top-image-size", 1.0), 1),
      )
  }

  private var appearance = Appearance()
  private val quadVertices: dynamic = js("new Float32Array(24)")
  private val quadUvs = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0)
  private val sectorUvs = DoubleArray(12) { quadUvs[it] * 2 - 1 }

  private val initialLocation = property("location")!!.jsonArray
  private val latitude = IndicatorAnimation(initialLocation[0].jsonPrimitive.double)
  private val longitude = IndicatorAnimation(initialLocation[1].jsonPrimitive.double)
  private val bearing = IndicatorAnimation(number("bearing"))
  private val accuracy = IndicatorAnimation(number("accuracy-radius"))
  private val sectorAngle =
    IndicatorPaint("bearing-accuracy", property("bearing-accuracy") ?: JsonPrimitive(0))
  private val sectorRadius =
    IndicatorPaint(
      "bearing-accuracy-radius",
      property("bearing-accuracy-radius") ?: JsonPrimitive(0),
    )
  private val sectorColor =
    IndicatorPaint(
      "bearing-accuracy-color",
      property("bearing-accuracy-color") ?: JsonPrimitive("white"),
      color = true,
    )
  private val sectorPaint =
    mapOf(
      "bearing-accuracy" to sectorAngle,
      "bearing-accuracy-radius" to sectorRadius,
      "bearing-accuracy-color" to sectorColor,
    )
  private var gl: dynamic = null
  private var vao: dynamic = null
  private var quadBuffer: dynamic = null
  private var accuracyBuffer: dynamic = null
  private var meshLatitude = Double.NaN
  private var meshRadius = Double.NaN
  private var accuracyVertexCount = 0
  internal var accuracyUploadCount = 0
    private set

  private var emptyTexture: dynamic = null
  private var feedback: dynamic = null
  private var feedbackBuffer: dynamic = null
  private var feedbackCapacity = 0

  private inner class Program(val handle: dynamic) {
    private val uniforms = mutableMapOf<String, dynamic>()

    fun uniform(name: String): dynamic =
      uniforms.getOrPut(name) { gl.getUniformLocation(handle, name) }
  }

  private val programs = mutableMapOf<String, Program>()

  private class Texture(val image: IndicatorImage, val handle: dynamic)

  private val textures = mutableMapOf<String, Texture>()
  private var subscriptions = emptyList<GlJsSubscription>()
  private var lost = false
  private var removed = false
  private var polygons: List<List<IndicatorPoint>>? = null
  private var hitCount = 0
  private var hitWidth = 0.0
  private var hitHeight = 0.0
  var renderedPosition: Position? = null
    private set

  internal var renderCount = 0
    private set

  internal var uploadCount = 0
    private set

  val layer: CustomLayerInterface = unsafeJso {
    this.id = this@GlJsLocationIndicator.id
    type = "custom"
    renderingMode = "2d"
    onAdd = { _, context ->
      gl = context
      removed = false
      subscriptions =
        listOf(
          map.subscribe("webglcontextlost") {
            lost = true
            release(false)
          },
          map.subscribe("webglcontextrestored") {
            lost = false
            map.triggerRepaint()
          },
        )
    }
    onRemove = { _, _ -> close() }
    render = { context, input ->
      gl = context
      if (!lost && !removed && context.isContextLost() != true) render(input)
    }
  }

  fun update(name: String, value: JsonElement, kind: LayerPropertyKind) {
    val old = property(name)
    val timing = property("$name-transition") as? JsonObject
    val delay = timing?.get("delay")?.jsonPrimitive?.doubleOrNull ?: 0.0
    val duration = timing?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 300.0
    val now = now()
    // Compile before publishing the definition so a rejected expression preserves the old value.
    if (value != old) sectorPaint[name]?.retarget(value, now, delay, duration)
    val section =
      when (kind) {
        LayerPropertyKind.Root -> null
        LayerPropertyKind.Paint -> "paint"
        LayerPropertyKind.Layout -> "layout"
      }
    definition =
      if (section == null) JsonObject(definition + (name to value))
      else {
        val values = definition[section]?.jsonObject.orEmpty() + (name to value)
        JsonObject(definition + (section to JsonObject(values)))
      }
    if (value != old) {
      appearance = Appearance()
      if (
        name.endsWith("-transition") &&
          value is JsonObject &&
          value["duration"]?.jsonPrimitive?.doubleOrNull == 0.0 &&
          (value["delay"]?.jsonPrimitive?.doubleOrNull ?: 0.0) == 0.0
      ) {
        sectorPaint[name.removeSuffix("-transition")]?.finish()
        when (name) {
          "location-transition" -> {
            latitude.finish()
            longitude.finish()
          }
          "bearing-transition" -> bearing.finish()
          "accuracy-radius-transition" -> accuracy.finish()
        }
      }
      when (name) {
        "location" -> {
          val location = value.jsonArray
          latitude.retarget(location[0].jsonPrimitive.double, now, delay, duration)
          longitude.retarget(location[1].jsonPrimitive.double, now, delay, duration, wrap = true)
        }
        "bearing" -> bearing.retarget(number(name), now, delay, duration, wrap = true)
        "accuracy-radius" -> accuracy.retarget(number(name), now, delay, duration)
      }
      // Hidden or replaced images must not remain clickable until a future render.
      if (name == "visibility" || name.endsWith("-image")) {
        polygons = null
        hitCount = 0
      }
      map.triggerRepaint()
    }
  }

  fun resourceChanged() {
    polygons = null
    hitCount = 0
    map.triggerRepaint()
  }

  fun propertyValue(name: String): JsonElement? = property(name)

  fun hitTest(left: Double, top: Double, right: Double, bottom: Double): Boolean {
    if (lost || removed || gl == null || gl.isContextLost() == true || !visible() || hitCount == 0)
      return false
    if (polygons == null) {
      // Read only on interaction, never on the animation/render critical path. Feedback retains
      // the exact last frame until the next draw, and all queries for that frame share the result.
      val previous = gl.getParameter(gl.COPY_READ_BUFFER_BINDING)
      try {
        gl.bindBuffer(gl.COPY_READ_BUFFER, feedbackBuffer)
        val corners = js("new Float32Array(24)")
        polygons =
          (0 until hitCount).flatMap { index ->
            gl.getBufferSubData(gl.COPY_READ_BUFFER, index * 96, corners)
            indicatorClipTriangles(corners, hitWidth, hitHeight)
          }
      } finally {
        gl.bindBuffer(gl.COPY_READ_BUFFER, previous)
      }
    }
    return polygons.orEmpty().any { indicatorIntersects(it, left, top, right, bottom) }
  }

  private fun visible() =
    appearance.visible && map.getZoom() >= appearance.minZoom && map.getZoom() < appearance.maxZoom

  fun close() {
    if (removed) return
    removed = true
    subscriptions.forEach { it.cancel() }
    subscriptions = emptyList()
    release(!lost)
  }

  private fun release(delete: Boolean) {
    if (delete && gl != null) {
      programs.values.forEach { gl.deleteProgram(it.handle) }
      textures.values.forEach { gl.deleteTexture(it.handle) }
      gl.deleteTexture(emptyTexture)
      gl.deleteBuffer(quadBuffer)
      gl.deleteBuffer(accuracyBuffer)
      gl.deleteBuffer(feedbackBuffer)
      gl.deleteTransformFeedback(feedback)
      gl.deleteVertexArray(vao)
    }
    programs.clear()
    textures.clear()
    emptyTexture = null
    vao = null
    quadBuffer = null
    accuracyBuffer = null
    meshLatitude = Double.NaN
    meshRadius = Double.NaN
    feedback = null
    feedbackBuffer = null
    feedbackCapacity = 0
    polygons = null
    hitCount = 0
    renderedPosition = null
  }

  /** Measurements are sampled once per frame; distances below use meters or CSS pixels. */
  private inner class Measurements(input: CustomRenderMethodInput) {
    private val time = now()
    val zoom = map.getZoom()
    private val globalState = map.getGlobalState()
    val globe = input.defaultProjectionData.projectionTransition > 0.0
    private val latitudeLimit = if (globe) 89.999999 else 85.0511287798066
    val lat = latitude.value(time).coerceIn(-latitudeLimit, latitudeLimit)
    val lng = longitude.value(time)
    val accuracyMeters = accuracy.value(time).coerceAtLeast(0.0)
    val bearingRadians = bearing.value(time) * PI / 180
    val sectorHalfAngle =
      sectorAngle.value(zoom, time, globalState)[0].coerceIn(0.0, 180.0) * PI / 180
    val sectorPixels = sectorRadius.value(zoom, time, globalState)[0].coerceAtLeast(0.0)
    val sectorRgba = sectorColor.value(zoom, time, globalState)
    val centerX = (lng + 180) / 360
    val centerY = mercatorY(lat)
    val worldPixels = 512 * 2.0.pow(zoom)
    val width = map.getCanvas().clientWidth.toDouble()
    val height = map.getCanvas().clientHeight.toDouble()

    init {
      renderedPosition = Position(lng, lat)
      if (
        listOf(latitude, longitude, bearing, accuracy).any { it.active(time) } ||
          sectorPaint.values.any { it.active(time) }
      )
        map.triggerRepaint()
    }
  }

  private fun worldCopies(frame: Measurements): IntRange {
    val nearest = round((map.getCenter().lng - frame.lng) / 360).toInt()
    return when {
      frame.globe -> nearest..nearest
      !map.getRenderWorldCopies() -> {
        val canonical = -floor(frame.centerX).toInt()
        canonical..canonical
      }
      else -> {
        val count = ceil(frame.width / frame.worldPixels).toInt() + 1
        (nearest - count)..(nearest + count)
      }
    }
  }

  /** World coordinates are Mercator fractions; vertices are in this copy's 8192-unit tile. */
  private inner class TileFrame(frame: Measurements, copy: Int, input: CustomRenderMethodInput) {
    val x = frame.centerX + copy
    val y = frame.centerY
    private val tileZoom = floor(frame.zoom).toInt().coerceIn(0, 22)
    private val tiles = 2.0.pow(tileZoom)
    private val tileX = floor(x * tiles)
    private val tileY = floor(y * tiles).coerceIn(0.0, tiles - 1)
    val tileUnitsPerWorld = tiles * 8192
    val projection =
      input.getProjectionData(
        unsafeJso<CustomProjectionOptions> {
          tileID = unsafeJso {
            wrap = floor(tileX / tiles).toInt()
            canonical = unsafeJso {
              this.x = ((tileX % tiles + tiles) % tiles).toInt()
              y = tileY.toInt()
              z = tileZoom
            }
          }
          applyGlobeMatrix = true
        }
      )
    val center = local(x, y)
    val sine = sin(frame.bearingRadians)
    val cosine = cos(frame.bearingRadians)
    val worldPerPixel: Double
    val displacementX: Double
    val displacementY: Double

    init {
      val location = LngLat(frame.lng + copy * 360, frame.lat)
      val screen = map.project(location)
      val left =
        map.unproject(
          unsafeJso {
            x = screen.x - 1
            y = screen.y
          }
        )
      val longitudeDelta = ((left.lng - location.lng + 180) % 360 + 360) % 360 - 180
      val mapPixelsPerScreenPixel =
        hypot(longitudeDelta / 360, mercatorY(left.lat) - y) * frame.worldPixels
      // Compensated Mercator world fractions per CSS pixel.
      worldPerPixel =
        ((1 - appearance.compensation) +
          mapPixelsPerScreenPixel.coerceIn(0.8, 10.1) * appearance.compensation) / frame.worldPixels
      // Native chooses the direction at the viewport bottom to avoid horizon convergence.
      val bottom =
        map.unproject(
          unsafeJso {
            x = screen.x
            y = frame.height - 1
          }
        )
      val above =
        map.unproject(
          unsafeJso {
            x = screen.x
            y = frame.height - 2
          }
        )
      val shiftX = (above.lng - bottom.lng) / 360
      val shiftY = mercatorY(above.lat) - mercatorY(bottom.lat)
      val shiftLength = hypot(shiftX, shiftY)
      val displacement = map.getPitch() * PI / 180 * appearance.displacement * worldPerPixel
      displacementX = if (shiftLength > 0) shiftX / shiftLength * displacement else 0.0
      displacementY = if (shiftLength > 0) shiftY / shiftLength * displacement else 0.0
    }

    fun local(mx: Double, my: Double) =
      Pair((mx * tiles - tileX) * 8192, (my * tiles - tileY) * 8192)
  }

  private fun render(input: CustomRenderMethodInput) {
    renderCount++
    polygons = null
    hitCount = 0
    if (!visible()) return
    val frame = Measurements(input)
    val copies = worldCopies(frame)
    if (vao == null) {
      vao = gl.createVertexArray()
      quadBuffer = gl.createBuffer()
      accuracyBuffer = gl.createBuffer()
      feedback = gl.createTransformFeedback()
      feedbackBuffer = gl.createBuffer()
    }
    val program = programs.getOrPut(input.shaderData.variantName) { program(input.shaderData) }
    val oldVao = gl.getParameter(gl.VERTEX_ARRAY_BINDING)
    val oldFeedback = gl.getParameter(gl.TRANSFORM_FEEDBACK_BINDING)
    val oldActive = gl.getParameter(gl.ACTIVE_TEXTURE)
    gl.activeTexture(gl.TEXTURE0)
    val oldTexture = gl.getParameter(gl.TEXTURE_BINDING_2D)
    val oldSampler = gl.getParameter(gl.SAMPLER_BINDING)
    val oldUnpack = gl.getParameter(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL)
    try {
      gl.bindVertexArray(vao)
      gl.useProgram(program.handle)
      gl.enableVertexAttribArray(0)
      gl.enableVertexAttribArray(1)
      gl.disable(gl.DEPTH_TEST)
      gl.disable(gl.CULL_FACE)
      gl.disable(gl.STENCIL_TEST)
      gl.enable(gl.BLEND)
      gl.blendEquation(gl.FUNC_ADD)
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
      gl.bindSampler(0, null)
      bindEmptyTexture()
      gl.uniform1i(program.uniform("u_image"), 0)
      hitWidth = frame.width
      hitHeight = frame.height
      gl.uniform1f(
        program.uniform("u_pixel_ratio"),
        (gl.getParameter(gl.VIEWPORT)[2] as Double) / frame.width,
      )
      reservePickFeedback(copies.count())
      for (copy in copies) {
        val tile = TileFrame(frame, copy, input)
        uniforms(program, tile.projection)
        drawAccuracy(program, frame, tile)
        drawSector(program, frame, tile)
        drawImages(program, tile)
      }
      retireTextures()
    } finally {
      gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, oldFeedback)
      gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER, null)
      gl.bindVertexArray(oldVao)
      gl.bindTexture(gl.TEXTURE_2D, oldTexture)
      gl.bindSampler(0, oldSampler)
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, oldUnpack)
      gl.activeTexture(oldActive)
    }
  }

  private fun bindEmptyTexture() {
    if (emptyTexture == null) {
      emptyTexture = gl.createTexture()
      gl.bindTexture(gl.TEXTURE_2D, emptyTexture)
      gl.texImage2D(
        gl.TEXTURE_2D,
        0,
        gl.RGBA,
        1,
        1,
        0,
        gl.RGBA,
        gl.UNSIGNED_BYTE,
        js("new Uint8Array([0,0,0,0])"),
      )
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    }
    gl.bindTexture(gl.TEXTURE_2D, emptyTexture)
  }

  private fun reservePickFeedback(copies: Int) {
    // Two pickable image passes per copy, six vec4 clip-space corners per pass.
    val capacity = copies * 2 * 96
    if (capacity <= feedbackCapacity) return
    gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER, feedbackBuffer)
    gl.bufferData(gl.TRANSFORM_FEEDBACK_BUFFER, capacity, gl.DYNAMIC_READ)
    gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER, null)
    feedbackCapacity = capacity
  }

  /** Half extents and shifts are Mercator world fractions; UVs retain their pass's units. */
  private fun writeQuad(
    tile: TileFrame,
    halfWidth: Double,
    halfHeight: Double,
    shiftX: Double = 0.0,
    shiftY: Double = 0.0,
    uvs: DoubleArray = quadUvs,
  ) {
    for (i in 0 until 6) {
      val u = quadUvs[i * 2]
      val v = quadUvs[i * 2 + 1]
      val ox = (u * 2 - 1) * halfWidth
      val oy = (v * 2 - 1) * halfHeight
      val point =
        tile.local(
          tile.x + ox * tile.cosine - oy * tile.sine + shiftX,
          tile.y + ox * tile.sine + oy * tile.cosine + shiftY,
        )
      quadVertices[i * 4] = point.first
      quadVertices[i * 4 + 1] = point.second
      quadVertices[i * 4 + 2] = uvs[i * 2]
      quadVertices[i * 4 + 3] = uvs[i * 2 + 1]
    }
    bindVertices(quadBuffer)
    gl.bufferData(gl.ARRAY_BUFFER, quadVertices, gl.DYNAMIC_DRAW)
  }

  private fun drawSector(program: Program, frame: Measurements, tile: TileFrame) {
    if (!(frame.sectorHalfAngle > 0 && frame.sectorPixels > 0 && frame.sectorRgba[3] > 0)) return
    val radius = frame.sectorPixels * tile.worldPerPixel
    writeQuad(tile, radius, radius, uvs = sectorUvs)
    gl.uniform3f(program.uniform("u_geometry"), 0, 0, 1)
    gl.uniform1i(program.uniform("u_mode"), 2)
    gl.uniform1f(program.uniform("u_sector_angle"), frame.sectorHalfAngle)
    val rgba = frame.sectorRgba
    gl.uniform4f(program.uniform("u_fill"), rgba[0], rgba[1], rgba[2], rgba[3])
    gl.drawArrays(gl.TRIANGLES, 0, 6)
  }

  private fun drawImages(program: Program, tile: TileFrame) {
    gl.uniform3f(program.uniform("u_geometry"), 0, 0, 1)
    gl.uniform1i(program.uniform("u_mode"), 0)
    for (layer in appearance.images) {
      val imageId = layer.id ?: continue
      val data = image(imageId) ?: continue
      val texture = texture(imageId, data)
      val size = layer.size * tile.worldPerPixel / data.pixelRatio
      val halfWidth = data.pixels.width * size / 2
      val halfHeight = data.pixels.height * size / 2
      if (halfWidth <= 0 || halfHeight <= 0) continue
      writeQuad(
        tile,
        halfWidth,
        halfHeight,
        tile.displacementX * layer.shift,
        tile.displacementY * layer.shift,
      )
      gl.bindTexture(gl.TEXTURE_2D, texture)
      // Capture shader clip-space corners for globe transitions and horizon clipping.
      // Shadow is drawn first but does not participate in picking.
      if (layer.shift == -1) {
        gl.drawArrays(gl.TRIANGLES, 0, 6)
      } else {
        gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, feedback)
        gl.bindBufferRange(gl.TRANSFORM_FEEDBACK_BUFFER, 0, feedbackBuffer, hitCount * 96, 96)
        gl.beginTransformFeedback(gl.TRIANGLES)
        gl.drawArrays(gl.TRIANGLES, 0, 6)
        gl.endTransformFeedback()
        hitCount++
        gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 0, null)
        gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, null)
      }
    }
  }

  private fun retireTextures() {
    val used = appearance.images.mapNotNull { it.id }.toSet()
    for (key in textures.keys.toList()) if (key !in used || image(key) !== textures[key]?.image) {
      gl.deleteTexture(textures.remove(key)!!.handle)
    }
  }

  private fun bindVertices(buffer: dynamic) {
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 16, 0)
    gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 16, 8)
  }

  private fun drawAccuracy(program: Program, frame: Measurements, tile: TileFrame) {
    val radius = frame.accuracyMeters
    if (!(radius > 0)) return
    val lat = frame.lat
    bindVertices(accuracyBuffer)
    // Store offsets from the measurement. Camera movement, world copies and longitude changes
    // only change the projection/translation uniforms, not the spherical mesh or its GPU buffer.
    if (lat != meshLatitude || radius != meshRadius) {
      val rings = ceil(radius / 150000).toInt().coerceIn(1, 32)
      val segments = 128
      accuracyVertexCount = rings * segments * 6
      val length = accuracyVertexCount * 4
      val vertices: dynamic = js("new Float32Array(length)")
      val angularRadius = (radius / 6371008.8).coerceAtMost(PI - 1e-6)
      val phi = lat * PI / 180
      val centerY = mercatorY(lat)
      var index = 0
      fun vertex(r: Double, angle: Double) {
        val distance = angularRadius * r
        val p =
          asin(
            (sin(phi) * cos(distance) + cos(phi) * sin(distance) * cos(angle)).coerceIn(-1.0, 1.0)
          )
        val dl = atan2(sin(angle) * sin(distance) * cos(phi), cos(distance) - sin(phi) * sin(p))
        vertices[index++] = dl / (2 * PI)
        vertices[index++] = mercatorY(p * 180 / PI) - centerY
        vertices[index++] = r * sin(angle)
        vertices[index++] = r * cos(angle)
      }
      for (ring in 0 until rings) for (segment in 0 until segments) {
        val inner = ring.toDouble() / rings
        val outer = (ring + 1.0) / rings
        val a = segment * 2 * PI / segments
        val b = (segment + 1) * 2 * PI / segments
        vertex(inner, a)
        vertex(outer, a)
        vertex(outer, b)
        vertex(inner, a)
        vertex(outer, b)
        vertex(inner, b)
      }
      gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.DYNAMIC_DRAW)
      meshLatitude = lat
      meshRadius = radius
      accuracyUploadCount++
    }
    val (centerX, centerY) = tile.center
    gl.uniform3f(program.uniform("u_geometry"), centerX, centerY, tile.tileUnitsPerWorld)
    gl.uniform1i(program.uniform("u_mode"), 1)
    color(program, "u_fill", appearance.fill)
    color(program, "u_border", appearance.border)
    gl.drawArrays(gl.TRIANGLES, 0, accuracyVertexCount)
  }

  private val colors = mutableMapOf<String, Pair<String, DoubleArray>>()
  private val colorCanvas: dynamic = js("document.createElement('canvas')")

  private fun color(program: Program, uniform: String, css: String) {
    val rgba =
      colors[uniform]?.takeIf { it.first == css }?.second
        ?: run {
          val ctx = colorCanvas.getContext("2d")
          ctx.clearRect(0, 0, 1, 1)
          ctx.fillStyle = css
          ctx.fillRect(0, 0, 1, 1)
          val pixel = ctx.getImageData(0, 0, 1, 1).data
          val alpha = (pixel[3] as Int) / 255.0
          doubleArrayOf(
              (pixel[0] as Int) / 255.0 * alpha,
              (pixel[1] as Int) / 255.0 * alpha,
              (pixel[2] as Int) / 255.0 * alpha,
              alpha,
            )
            .also { colors[uniform] = css to it }
        }
    gl.uniform4f(program.uniform(uniform), rgba[0], rgba[1], rgba[2], rgba[3])
  }

  private fun texture(id: String, data: IndicatorImage): dynamic {
    val previous = textures[id]
    if (previous?.image === data) return previous.handle
    if (previous != null) gl.deleteTexture(previous.handle)
    val texture = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    // Typed-array uploads stay straight alpha; the fragment shader premultiplies once.
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(
      gl.TEXTURE_2D,
      0,
      gl.RGBA,
      data.pixels.width.toInt(),
      data.pixels.height.toInt(),
      0,
      gl.RGBA,
      gl.UNSIGNED_BYTE,
      data.pixels.data,
    )
    textures[id] = Texture(data, texture)
    uploadCount++
    return texture
  }

  private fun uniforms(program: Program, data: CustomProjectionData) {
    gl.uniformMatrix4fv(
      program.uniform("u_projection_matrix"),
      false,
      floatCopy(data.mainMatrix),
    )
    gl.uniformMatrix4fv(
      program.uniform("u_projection_fallback_matrix"),
      false,
      floatCopy(data.fallbackMatrix),
    )
    gl.uniform4fv(
      program.uniform("u_projection_tile_mercator_coords"),
      floats(data.tileMercatorCoords.toList()),
    )
    gl.uniform4fv(
      program.uniform("u_projection_clipping_plane"),
      floats(data.clippingPlane.toList()),
    )
    gl.uniform1f(
      program.uniform("u_projection_transition"),
      data.projectionTransition,
    )
  }

  private fun program(data: CustomShaderData): Program {
    fun shader(type: Int, source: String): dynamic {
      val shader = gl.createShader(type)
      gl.shaderSource(shader, source)
      gl.compileShader(shader)
      check(gl.getShaderParameter(shader, gl.COMPILE_STATUS) == true) {
        gl.getShaderInfoLog(shader) as String
      }
      return shader
    }
    val vertex =
      shader(
        gl.VERTEX_SHADER as Int,
        """#version 300 es
      ${data.vertexShaderPrelude}
      ${data.define}
      layout(location=0) in vec2 a_pos;
      layout(location=1) in vec2 a_uv;
      uniform vec3 u_geometry;
      out vec2 v_uv;
      out vec4 v_clip;
      void main() { v_uv = a_uv; gl_Position = projectTile(a_pos * u_geometry.z + u_geometry.xy); v_clip = gl_Position; }
    """
          .trimIndent(),
      )
    val fragment =
      shader(
        gl.FRAGMENT_SHADER as Int,
        """
        #version 300 es
        precision highp float;
        in vec2 v_uv;
        uniform sampler2D u_image;
        uniform int u_mode;
        uniform vec4 u_fill;
        uniform vec4 u_border;
        uniform float u_pixel_ratio;
        uniform float u_sector_angle;
        out vec4 fragColor;
        void main() {
          if (u_mode == 0) {
            vec4 c = texture(u_image, v_uv);
            fragColor = vec4(c.rgb * c.a, c.a);
          } else if (u_mode == 2) {
            float radius = length(v_uv);
            float angle = atan(max(abs(v_uv.x), 0.000001), -v_uv.y);
            float feather = length(fwidth(v_uv)) / max(radius, 0.0001);
            float angular = u_sector_angle >= 3.14159265 ? 1.0 :
                1.0 - smoothstep(u_sector_angle - feather, u_sector_angle + feather, angle);
            fragColor = u_fill * angular * (1.0 - smoothstep(0.0, 1.0, radius));
          } else {
            float r = length(v_uv);
            float aa = max(fwidth(r), 0.000001);
            float edge = 1.0 - smoothstep(1.0-aa, 1.0, r);
            float border = smoothstep(1.0-(u_pixel_ratio+1.0)*aa, 1.0-u_pixel_ratio*aa, r);
            fragColor = mix(u_fill, u_border, border) * edge;
          }
        }
        """
          .trimIndent(),
      )
    val program = gl.createProgram()
    gl.attachShader(program, vertex)
    gl.attachShader(program, fragment)
    gl.transformFeedbackVaryings(program, arrayOf("v_clip"), gl.INTERLEAVED_ATTRIBS)
    gl.linkProgram(program)
    gl.deleteShader(vertex)
    gl.deleteShader(fragment)
    check(gl.getProgramParameter(program, gl.LINK_STATUS) == true) {
      gl.getProgramInfoLog(program) as String
    }
    return Program(program)
  }
}

private fun now(): Double = js("performance.now()") as Double

private fun floatCopy(values: dynamic): dynamic = js("new Float32Array(values)")

private fun floats(values: List<Double>): dynamic = floatCopy(values.toTypedArray())

private fun mercatorY(latitude: Double): Double {
  val lat = latitude.coerceIn(-89.999999, 89.999999) * PI / 180
  return (1 - ln(tan(PI / 4 + lat / 2)) / PI) / 2
}
