package org.maplibre.kmp.native.map

import smjni.jnigen.CalledByNative
import smjni.jnigen.ExposeToNative

@ExposeToNative
public data class RenderingStats
@CalledByNative
public constructor(
  val encodingTime: Double,
  val renderingTime: Double,
  val numFrames: Int,
  val numDrawCalls: Int,
  val totalDrawCalls: Int,
  val numCreatedTextures: Int,
  val numActiveTextures: Int,
  val numTextureBindings: Int,
  val numTextureUpdates: Int,
  val textureUpdateBytes: Long,
  val totalBuffers: Long,
  val totalBufferObjs: Long,
  val bufferUpdates: Long,
  val bufferObjUpdates: Long,
  val bufferUpdateBytes: Long,
  val numBuffers: Int,
  val numFrameBuffers: Int,
  val numIndexBuffers: Int,
  val indexUpdateBytes: Long,
  val numVertexBuffers: Int,
  val vertexUpdateBytes: Long,
  val numUniformBuffers: Int,
  val numUniformUpdates: Int,
  val uniformUpdateBytes: Long,
  val memTextures: Int,
  val memBuffers: Int,
  val memIndexBuffers: Int,
  val memVertexBuffers: Int,
  val memUniformBuffers: Int,
  val stencilClears: Int,
  val stencilUpdates: Int,
)
