import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Packages the pinned ANGLE distribution with its license for the macOS OpenGL runtime. */
@CacheableTask
abstract class PackageAngle : DefaultTask() {
  @get:Input abstract val angleVersion: Property<String>
  @get:Input abstract val sha256: Property<String>
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
  @get:Inject abstract val archives: ArchiveOperations
  @get:Inject abstract val files: FileSystemOperations

  @TaskAction
  fun packageRuntime() {
    val archive = temporaryDir.resolve("angle-macos-arm64.tar.gz")
    val url =
      URI(
        "https://github.com/kivy/angle-builder/releases/download/${angleVersion.get()}/angle-macos-arm64.tar.gz"
      )
    url.toURL().openStream().use { input -> archive.outputStream().use(input::copyTo) }
    val digest = MessageDigest.getInstance("SHA-256")
    archive.inputStream().use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } == sha256.get()) {
      "ANGLE archive checksum does not match"
    }
    files.sync {
      from(archives.tarTree(archive))
      into(outputDirectory)
      include("**/libEGL.dylib", "**/libGLESv2.dylib", "**/LICENSE")
      includeEmptyDirs = false
      eachFile {
        path =
          if (name == "LICENSE") "META-INF/licenses/maplibre-compose/angle.txt"
          else "META-INF/maplibre-compose/angle/macos-arm64/$name"
      }
    }
    for (name in listOf("libEGL.dylib", "libGLESv2.dylib")) {
      check(
        outputDirectory
          .file("META-INF/maplibre-compose/angle/macos-arm64/$name")
          .get()
          .asFile
          .isFile
      ) {
        "ANGLE archive is missing $name"
      }
    }
  }
}
