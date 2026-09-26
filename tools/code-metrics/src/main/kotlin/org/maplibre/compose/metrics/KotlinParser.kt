package org.maplibre.compose.metrics

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Parses Kotlin source into PSI with the same compiler the build uses, so every construct the build
 * accepts is measured. No classpath is loaded: the tree carries syntax only, not types.
 */
class KotlinParser : AutoCloseable {
  private val disposable = Disposer.newDisposable("code-metrics")
  private val factory: KtPsiFactory

  init {
    // The replacement is the Analysis API standalone session, which needs artifacts outside Maven
    // Central and a module model this tool has no use for. Syntax-only parsing is all it needs.
    @OptIn(CoreEnvironmentDeprecation::class)
    val environment =
      KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration.create(),
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
      )
    factory = KtPsiFactory(environment.project, markGenerated = false)
  }

  fun parse(fileName: String, text: String): KtFile = factory.createPhysicalFile(fileName, text)

  override fun close() = Disposer.dispose(disposable)
}
