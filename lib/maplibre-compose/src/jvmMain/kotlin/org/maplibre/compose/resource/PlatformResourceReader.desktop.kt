package org.maplibre.compose.resource

import java.io.FileNotFoundException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.NoSuchFileException

internal actual fun readPlatformResourceBytes(url: String): ByteArray =
  try {
    URI(url).toURL().openStream().use { it.readBytes() }
  } catch (error: Throwable) {
    if (error is VirtualMachineError) throw error
    throw MlnFfiResourceReadException(classify(error), error)
  }

private fun classify(error: Throwable): MlnFfiResourceReadFailure =
  when (error) {
    // A jar whose backing file is missing arrives as `NoSuchFileException` rather than
    // `FileNotFoundException`.
    is FileNotFoundException,
    is NoSuchFileException -> MlnFfiResourceReadFailure.NOT_FOUND
    is URISyntaxException -> MlnFfiResourceReadFailure.INVALID_URL
    else -> MlnFfiResourceReadFailure.UNREADABLE
  }
