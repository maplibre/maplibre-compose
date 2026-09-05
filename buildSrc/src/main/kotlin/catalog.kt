import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Reads a catalog version for precompiled plugins, which have no typesafe `libs` accessor. */
fun Project.catalogVersion(name: String): String =
  extensions
    .getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion(name)
    .orElseThrow { IllegalStateException("Missing version catalog entry: $name") }
    .requiredVersion

fun Project.catalogVersionInt(name: String): Int = catalogVersion(name).toInt()
