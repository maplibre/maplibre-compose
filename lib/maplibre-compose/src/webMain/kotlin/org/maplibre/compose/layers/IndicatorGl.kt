package org.maplibre.compose.layers

import kotlin.js.JsAny

/** WebGL calls used by the location indicator, with JavaScript numeric arguments. */
internal external interface IndicatorGl : JsAny {
  val ACTIVE_TEXTURE: Int
  val ARRAY_BUFFER: Int
  val BLEND: Int
  val CLAMP_TO_EDGE: Int
  val COMPILE_STATUS: Int
  val COPY_READ_BUFFER: Int
  val COPY_READ_BUFFER_BINDING: Int
  val CULL_FACE: Int
  val DEPTH_TEST: Int
  val DYNAMIC_DRAW: Int
  val DYNAMIC_READ: Int
  val FLOAT: Int
  val FRAGMENT_SHADER: Int
  val FUNC_ADD: Int
  val INTERLEAVED_ATTRIBS: Int
  val LINEAR: Int
  val LINK_STATUS: Int
  val NEAREST: Int
  val ONE: Int
  val ONE_MINUS_SRC_ALPHA: Int
  val RGBA: Int
  val SAMPLER_BINDING: Int
  val STENCIL_TEST: Int
  val TEXTURE0: Int
  val TEXTURE_2D: Int
  val TEXTURE_BINDING_2D: Int
  val TEXTURE_MAG_FILTER: Int
  val TEXTURE_MIN_FILTER: Int
  val TEXTURE_WRAP_S: Int
  val TEXTURE_WRAP_T: Int
  val TRANSFORM_FEEDBACK: Int
  val TRANSFORM_FEEDBACK_BINDING: Int
  val TRANSFORM_FEEDBACK_BUFFER: Int
  val TRIANGLES: Int
  val UNPACK_PREMULTIPLY_ALPHA_WEBGL: Int
  val UNSIGNED_BYTE: Int
  val VERTEX_ARRAY_BINDING: Int
  val VERTEX_SHADER: Int
  val VIEWPORT: Int

  fun isContextLost(): Boolean

  fun getParameter(parameter: Int): JsAny?

  fun createVertexArray(): JsAny?

  fun createBuffer(): JsAny?

  fun createTransformFeedback(): JsAny?

  fun createTexture(): JsAny?

  fun createShader(type: Int): JsAny?

  fun createProgram(): JsAny?

  fun getUniformLocation(program: JsAny?, name: String): JsAny?

  fun deleteProgram(program: JsAny?)

  fun deleteTexture(texture: JsAny?)

  fun deleteBuffer(buffer: JsAny?)

  fun deleteTransformFeedback(feedback: JsAny?)

  fun deleteVertexArray(vao: JsAny?)

  fun deleteShader(shader: JsAny?)

  fun bindVertexArray(vao: JsAny?)

  fun useProgram(program: JsAny?)

  fun enableVertexAttribArray(index: Int)

  fun disable(capability: Int)

  fun enable(capability: Int)

  fun blendEquation(mode: Int)

  fun blendFunc(source: Int, destination: Int)

  fun bindSampler(unit: Int, sampler: JsAny?)

  fun activeTexture(texture: Int)

  fun bindTexture(target: Int, texture: JsAny?)

  fun pixelStorei(parameter: Int, value: Boolean)

  fun bindTransformFeedback(target: Int, feedback: JsAny?)

  fun bindBuffer(target: Int, buffer: JsAny?)

  fun bindBufferRange(target: Int, index: Int, buffer: JsAny?, offset: Int, size: Int)

  fun bindBufferBase(target: Int, index: Int, buffer: JsAny?)

  fun beginTransformFeedback(mode: Int)

  fun endTransformFeedback()

  fun bufferData(target: Int, size: Int, usage: Int)

  fun bufferData(target: Int, data: JsAny, usage: Int)

  fun getBufferSubData(target: Int, offset: Int, data: JsAny)

  fun vertexAttribPointer(
    index: Int,
    size: Int,
    type: Int,
    normalized: Boolean,
    stride: Int,
    offset: Int,
  )

  fun drawArrays(mode: Int, first: Int, count: Int)

  fun texImage2D(
    target: Int,
    level: Int,
    internalFormat: Int,
    width: Int,
    height: Int,
    border: Int,
    format: Int,
    type: Int,
    pixels: JsAny?,
  )

  fun texParameteri(target: Int, parameter: Int, value: Int)

  fun uniform1i(location: JsAny?, value: Int)

  fun uniform1f(location: JsAny?, value: Double)

  fun uniform3f(location: JsAny?, x: Double, y: Double, z: Double)

  fun uniform4f(location: JsAny?, x: Double, y: Double, z: Double, w: Double)

  fun uniform4fv(location: JsAny?, values: JsAny)

  fun uniformMatrix4fv(location: JsAny?, transpose: Boolean, values: JsAny)

  fun shaderSource(shader: JsAny?, source: String)

  fun compileShader(shader: JsAny?)

  fun getShaderParameter(shader: JsAny?, parameter: Int): Boolean

  fun getShaderInfoLog(shader: JsAny?): String?

  fun attachShader(program: JsAny?, shader: JsAny?)

  fun transformFeedbackVaryings(program: JsAny?, varyings: JsAny, mode: Int)

  fun linkProgram(program: JsAny?)

  fun getProgramParameter(program: JsAny?, parameter: Int): Boolean

  fun getProgramInfoLog(program: JsAny?): String?
}
