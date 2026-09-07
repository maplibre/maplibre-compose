import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Applies the in-repo MapLibre `expr { }` compiler plugin to Kotlin compilations in this project.
 *
 * JVM, JS, Android, and metadata compilations use `pluginClasspath`. Native compilations get
 * `-Xplugin` because they use a different task type.
 */
fun Project.applyMapLibreExprCompilerPlugin() {
  val compilerPath = ":lib:maplibre-compose-expr-compiler"
  val compiler = rootProject.findProject(compilerPath) ?: return
  evaluationDependsOn(compilerPath)

  val jar = compiler.tasks.named("jar", Jar::class)

  tasks.withType<AbstractKotlinCompile<*>>().configureEach { pluginClasspath.from(jar) }

  tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(jar)
    if (this !is AbstractKotlinCompile<*>) {
      val pluginArg = jar.flatMap { it.archiveFile }.map { "-Xplugin=${it.asFile.absolutePath}" }
      compilerOptions.freeCompilerArgs.add(pluginArg)
    }
  }
}
