import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity

/**
 * Rebuild the browser test bundle when any bundled module changes, not only the test entry file,
 * and serve the pinned Mocha dependency instead of the runner template's unversioned CDN URLs.
 */
fun Project.configureBrowserTestBundle() {
  val mochaDirectory = rootProject.layout.buildDirectory.dir("js/node_modules/mocha")
  tasks.named("prepareWebpackBundleForKotlinJsTests") {
    inputs
      .files(tasks.named("jsTestTestDevelopmentExecutableCompileSync"))
      .withPropertyName("testRuntime")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
      mochaDirectory.map { it.file("mocha.js") },
      mochaDirectory.map { it.file("mocha.css") },
    )
    val bundleDirectory = layout.buildDirectory.dir("kotlinJsTest/dist")
    doLast {
      val bundle = bundleDirectory.get().asFile
      for (name in listOf("mocha.js", "mocha.css")) {
        mochaDirectory.get().file(name).asFile.copyTo(bundle.resolve(name), overwrite = true)
      }
      val html = bundle.resolve("test.html")
      html.writeText(html.readText().replace("https://unpkg.com/mocha/", "./"))
    }
  }
}
