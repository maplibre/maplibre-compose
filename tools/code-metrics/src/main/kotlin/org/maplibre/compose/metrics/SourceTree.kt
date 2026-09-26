package org.maplibre.compose.metrics

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/** A Kotlin file under a Gradle module's `src/<sourceSet>/kotlin` directory. */
data class SourceFile(
  val path: Path,
  /** Repository-relative path with `/` separators. */
  val relativePath: String,
  /** Repository-relative module directory, such as `lib/maplibre-compose`. */
  val module: String,
  val sourceSet: String,
) {
  val isTest: Boolean
    get() = sourceSet == "test" || sourceSet.endsWith("Test")
}

private val skippedDirectories =
  setOf("build", ".gradle", ".kotlin", "node_modules", ".git", ".claude")

/**
 * Finds every Kotlin file under `<root>/<scanRoot>/**/src/<sourceSet>/kotlin`, without descending
 * into build output.
 */
fun discoverSourceFiles(root: Path, scanRoots: List<String>): List<SourceFile> {
  val files = mutableListOf<SourceFile>()
  val visitor =
    object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        // Output directories are pruned outside source trees only; a package may be named `build`.
        val segments = dir.relativeTo(root).map { it.name }
        val prune = dir.name in skippedDirectories && sourceTreeIndex(segments) == null
        return if (prune) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
      }

      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (attrs.isRegularFile && file.name.endsWith(".kt"))
          sourceFile(root, file)?.let(files::add)
        return FileVisitResult.CONTINUE
      }
    }
  for (scanRoot in scanRoots.map(root::resolve).filter { it.isDirectory() }) {
    Files.walkFileTree(scanRoot, visitor)
  }
  // Overlapping roots, such as `lib` and `lib/x`, reach the same files twice.
  return files.distinctBy { it.relativePath }.sortedBy { it.relativePath }
}

private fun sourceFile(root: Path, path: Path): SourceFile? {
  val segments = path.relativeTo(root).map { it.name }
  val srcIndex = sourceTreeIndex(segments) ?: return null
  return SourceFile(
    path = path,
    relativePath = segments.joinToString("/"),
    // A module at the repository root has no directory of its own.
    module = segments.subList(0, srcIndex).joinToString("/").ifEmpty { "." },
    sourceSet = segments[srcIndex + 1],
  )
}

/**
 * The index of the first `src/<sourceSet>/kotlin` triple in [segments] that leaves something after
 * it, or null. The first one, since a package directory may itself be named `src`.
 */
private fun sourceTreeIndex(segments: List<String>): Int? =
  (0 until segments.size - 3).firstOrNull {
    segments[it] == "src" && segments[it + 2] == "kotlin"
  }
