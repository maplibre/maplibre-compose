package org.maplibre.compose.util

import java.io.InputStream
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse

internal class JarResourceHandler : CefResourceHandler {
  private var inputStream: InputStream? = null
  private var mimeType: String? = null
  private var responseLength = 0

  override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
    return try {
      val uri = URI(request.url)

      val fileSystem =
        try {
          FileSystems.getFileSystem(uri)
        } catch (e: FileSystemNotFoundException) {
          FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        }

      val pathInJar = fileSystem.getPath(uri.schemeSpecificPart.substringAfter("!/"))

      inputStream = Files.newInputStream(pathInJar)
      mimeType = Files.probeContentType(pathInJar) ?: "application/octet-stream"
      responseLength = Files.size(pathInJar).toInt()

      callback.Continue()
      true
    } catch (e: Exception) {
      e.printStackTrace()
      callback.cancel()
      false
    }
  }

  override fun getResponseHeaders(
    response: CefResponse,
    responseLength: IntRef,
    redirectUrl: StringRef,
  ) {
    response.mimeType = mimeType
    response.status = 200
    response.statusText = "OK"
    response.setHeaderByName("Access-Control-Allow-Origin", "*", true)
    responseLength.set(this.responseLength)
  }

  override fun readResponse(
    dataOut: ByteArray,
    bytesToRead: Int,
    bytesRead: IntRef,
    callback: CefCallback,
  ): Boolean {
    val inputStream =
      inputStream
        ?: run {
          bytesRead.set(0)
          return false
        }

    return try {
      val availableBytes = inputStream.read(dataOut, 0, bytesToRead)
      if (availableBytes == -1) {
        inputStream.close()
        this.inputStream = null
        bytesRead.set(0)
        false
      } else {
        bytesRead.set(availableBytes)
        true
      }
    } catch (e: Exception) {
      e.printStackTrace()
      bytesRead.set(0)
      false
    }
  }

  override fun cancel() {
    inputStream?.close()
    inputStream = null
  }
}
