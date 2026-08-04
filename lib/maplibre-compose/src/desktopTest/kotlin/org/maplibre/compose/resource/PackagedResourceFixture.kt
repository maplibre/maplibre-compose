package org.maplibre.compose.resource

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Real files to resolve, in the two shapes an application ships resources in.
 *
 * Everything here is on disk rather than asserted as a string, because what the provider is being
 * asked to get right is the encode/decode round trip between a URI and a filesystem: a path holding
 * a space and a non-ASCII character survives every string comparison and still fails to open.
 */
internal class PackagedResourceFixture : AutoCloseable {

  /**
   * A directory whose own name has a space and characters outside Latin-1 in it, so that every path
   * built under it is percent-encoded when it becomes a URI.
   */
  val root: Path = Files.createTempDirectory("maplibre resources ké地図")

  /** Writes [content] to [relativePath] under [root] and returns the file's URI. */
  fun file(relativePath: String, content: String): String {
    val file = root.resolve(relativePath)
    Files.createDirectories(file.parent)
    Files.writeString(file, content)
    return file.toUri().toString()
  }

  /**
   * Builds a real jar holding [entries] and returns the `jar:file:` URI of [entryPath].
   *
   * A real jar rather than a stub, because a `jar:` URL is resolved by a different JDK handler than
   * a `file:` one — it splits the URL at `!/`, opens the outer file, and decodes the entry name
   * separately — and only an actual archive exercises that.
   */
  fun jarEntry(jarName: String, entryPath: String, entries: Map<String, String>): String {
    val jar = root.resolve(jarName)
    Files.createDirectories(jar.parent)
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      entries.forEach { (name, content) ->
        out.putNextEntry(ZipEntry(name))
        out.write(content.toByteArray())
        out.closeEntry()
      }
    }
    return jarUri(jar, entryPath)
  }

  /** The URI of [entryPath] inside [jar], with the entry name encoded as a URI path. */
  fun jarUri(jar: Path, entryPath: String): String =
    "jar:${jar.toUri()}!/${URI(null, null, entryPath, null).toASCIIString()}"

  /** The path of a file under [root] that was never created. */
  fun missing(relativePath: String): String = root.resolve(relativePath).toUri().toString()

  override fun close() {
    root.toFile().deleteRecursively()
  }
}
