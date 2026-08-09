import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * Reads a version out of `gradle/libs.versions.toml`.
 *
 * Precompiled script plugins get no typesafe `libs` accessor, so the convention plugins in this
 * directory go through here. Ordinary project build scripts use `libs` directly.
 */
fun Project.catalogVersion(name: String): String =
  extensions
    .getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion(name)
    .orElseThrow { IllegalStateException("Missing version catalog entry: $name") }
    .requiredVersion

fun Project.catalogVersionInt(name: String): Int = catalogVersion(name).toInt()
