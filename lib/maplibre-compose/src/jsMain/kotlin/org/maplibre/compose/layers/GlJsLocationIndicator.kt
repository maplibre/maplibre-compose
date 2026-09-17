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
  private val imageVertices: dynamic = js("new Float32Array(24)")
  private val imageUvs = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0)

  private val initialLocation = property("location")!!.jsonArray
  private val latitude = IndicatorAnimation(initialLocation[0].jsonPrimitive.double)
  private val longitude = IndicatorAnimation(initialLocation[1].jsonPrimitive.double)
  private val bearing = IndicatorAnimation(number("bearing"))
  private val accuracy = IndicatorAnimation(number("accuracy-radius"))
  private var gl: dynamic = null
  private var vao: dynamic = null
  private var buffer: dynamic = null
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
    val section =
      when (kind) {
        LayerPropertyKind.ROOT -> null
        LayerPropertyKind.PAINT -> "paint"
        LayerPropertyKind.LAYOUT -> "layout"
      }
    definition =
      if (section == null) JsonObject(definition + (name to value))
      else {
        val values = definition[section]?.jsonObject.orEmpty() + (name to value)
        JsonObject(definition + (section to JsonObject(values)))
      }
    if (value != old) {
      appearance = Appearance()
      val timing = property("$name-transition") as? JsonObject
      val delay = timing?.get("delay")?.jsonPrimitive?.doubleOrNull ?: 0.0
      val duration = timing?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 300.0
      if (
        name.endsWith("-transition") &&
          value is JsonObject &&
          value["duration"]?.jsonPrimitive?.doubleOrNull == 0.0 &&
          (value["delay"]?.jsonPrimitive?.doubleOrNull ?: 0.0) == 0.0
      ) {
        when (name) {
          "location-transition" -> {
            latitude.finish()
            longitude.finish()
          }
          "bearing-transition" -> bearing.finish()
          "accuracy-radius-transition" -> accuracy.finish()
        }
      }
      val now = now()
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
      gl.deleteBuffer(buffer)
      gl.deleteBuffer(accuracyBuffer)
      gl.deleteBuffer(feedbackBuffer)
      gl.deleteTransformFeedback(feedback)
      gl.deleteVertexArray(vao)
    }
    programs.clear()
    textures.clear()
    emptyTexture = null
    vao = null
    buffer = null
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

  private fun render(input: CustomRenderMethodInput) {
    renderCount++
    polygons = null
    hitCount = 0
    if (!visible()) return
    val now = now()
    val globe = input.defaultProjectionData.projectionTransition > 0.0
    val latitudeLimit = if (globe) 89.999999 else 85.0511287798066
    val lat = latitude.value(now).coerceIn(-latitudeLimit, latitudeLimit)
    val lng = longitude.value(now)
    renderedPosition = Position(lng, lat)
    if (listOf(latitude, longitude, bearing, accuracy).any { it.active(now) }) map.triggerRepaint()
    if (vao == null) {
      vao = gl.createVertexArray()
      buffer = gl.createBuffer()
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
      gl.uniform1i(program.uniform("u_image"), 0)
      val centerX = (lng + 180) / 360
      val centerY = mercatorY(lat)
      val nearest = round((map.getCenter().lng - lng) / 360).toInt()
      val worldSize = 512 * 2.0.pow(map.getZoom())
      val canvas = map.getCanvas()
      val width = canvas.clientWidth.toDouble()
      val height = canvas.clientHeight.toDouble()
      val copies =
        if (globe) nearest..nearest
        else if (!map.getRenderWorldCopies()) {
          val canonical = -floor(centerX).toInt()
          canonical..canonical
        } else {
          val count = ceil(width / worldSize).toInt() + 1
          (nearest - count)..(nearest + count)
        }
      hitWidth = width
      hitHeight = height
      gl.uniform1f(
        program.uniform("u_pixel_ratio"),
        (gl.getParameter(gl.VIEWPORT)[2] as Double) / width,
      )
      val capacity = copies.count() * 2 * 96
      if (capacity > feedbackCapacity) {
        gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER, feedbackBuffer)
        gl.bufferData(gl.TRANSFORM_FEEDBACK_BUFFER, capacity, gl.DYNAMIC_READ)
        gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER, null)
        feedbackCapacity = capacity
      }
      for (copy in copies) {
        val x = centerX + copy
        // Tile-local vertices retain subpixel precision at high zoom.
        val tileZoom = floor(map.getZoom()).toInt().coerceIn(0, 22)
        val tiles = 2.0.pow(tileZoom)
        val tileX = floor(x * tiles)
        val tileY = floor(centerY * tiles).coerceIn(0.0, tiles - 1)
        val options =
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
        val projection = input.getProjectionData(options)
        uniforms(program, projection)
        fun local(mx: Double, my: Double) =
          Pair((mx * tiles - tileX) * 8192, (my * tiles - tileY) * 8192)
        val radius = accuracy.value(now).coerceAtLeast(0.0)
        if (radius > 0) {
          drawAccuracy(program, lat, radius, local(x, centerY), tiles * 8192)
        }
        val location = LngLat(lng + copy * 360, lat)
        val screen = map.project(location)
        val left =
          map.unproject(
            unsafeJso {
              this.x = screen.x - 1
              y = screen.y
            }
          )
        val longitudeDelta = ((left.lng - location.lng + 180) % 360 + 360) % 360 - 180
        val mapPixelsPerScreenPixel =
          hypot(longitudeDelta / 360, mercatorY(left.lat) - centerY) * worldSize
        val compensation = appearance.compensation
        val scale = (1 - compensation) + mapPixelsPerScreenPixel.coerceIn(0.8, 10.1) * compensation
        // Native chooses the shift direction at the bottom of the viewport to avoid exaggerated
        // perspective convergence near the horizon.
        val bottom =
          map.unproject(
            unsafeJso {
              this.x = screen.x
              y = height - 1
            }
          )
        val above =
          map.unproject(
            unsafeJso {
              this.x = screen.x
              y = height - 2
            }
          )
        val shiftX = (above.lng - bottom.lng) / 360
        val shiftY = mercatorY(above.lat) - mercatorY(bottom.lat)
        val shiftLength = hypot(shiftX, shiftY)
        val displacement = map.getPitch() * PI / 180 * appearance.displacement * scale / worldSize
        val dx = if (shiftLength > 0) shiftX / shiftLength * displacement else 0.0
        val dy = if (shiftLength > 0) shiftY / shiftLength * displacement else 0.0
        bindVertices(buffer)
        gl.uniform3f(program.uniform("u_geometry"), 0, 0, 1)
        for (layer in appearance.images) {
          val imageId = layer.id ?: continue
          val data = image(imageId) ?: continue
          val texture = texture(imageId, data)
          val size = layer.size * scale / worldSize / data.pixelRatio
          val halfWidth = data.pixels.width * size / 2
          val halfHeight = data.pixels.height * size / 2
          if (halfWidth <= 0 || halfHeight <= 0) continue
          val angle = bearing.value(now) * PI / 180
          val sine = sin(angle)
          val cosine = cos(angle)
          for (i in 0 until 6) {
            val u = imageUvs[i * 2]
            val v = imageUvs[i * 2 + 1]
            val ox = (u * 2 - 1) * halfWidth
            val oy = (v * 2 - 1) * halfHeight
            val point =
              local(
                x + ox * cosine - oy * sine + dx * layer.shift,
                centerY + ox * sine + oy * cosine + dy * layer.shift,
              )
            imageVertices[i * 4] = point.first
            imageVertices[i * 4 + 1] = point.second
            imageVertices[i * 4 + 2] = u
            imageVertices[i * 4 + 3] = v
          }
          gl.uniform1i(program.uniform("u_mode"), 0)
          gl.bindTexture(gl.TEXTURE_2D, texture)
          gl.bufferData(gl.ARRAY_BUFFER, imageVertices, gl.DYNAMIC_DRAW)
          // Capture the shader's actual clip-space corners. This keeps picking identical to the
          // public projection prelude, including globe transitions and horizon clipping.
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
            gl.bindTransformFeedback(gl.TRANSFORM_FEEDBACK, oldFeedback)
          }
        }
      }
      // Images no longer referenced by the layer need no GPU storage.
      val used = appearance.images.mapNotNull { it.id }.toSet()
      for (key in textures.keys.toList()) if (key !in used || image(key) !== textures[key]?.image) {
        gl.deleteTexture(textures.remove(key)!!.handle)
      }
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

  private fun bindVertices(buffer: dynamic) {
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 16, 0)
    gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 16, 8)
  }

  private fun drawAccuracy(
    program: Program,
    lat: Double,
    radius: Double,
    center: Pair<Double, Double>,
    scale: Double,
  ) {
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
    gl.uniform3f(program.uniform("u_geometry"), center.first, center.second, scale)
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
        out vec4 fragColor;
        void main() {
          if (u_mode == 0) {
            vec4 c = texture(u_image, v_uv);
            fragColor = vec4(c.rgb * c.a, c.a);
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
