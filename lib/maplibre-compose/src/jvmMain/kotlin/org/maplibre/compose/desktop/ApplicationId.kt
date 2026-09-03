package org.maplibre.compose.desktop

/** The default cache directory name: the package of the process `main` class. */
internal fun inferredApplicationId(): String {
  val className = mainClassName()
  val id = className?.let(::applicationIdFromClassName)
  return id
    ?: throw IllegalStateException(
      "Could not infer an application id from the process main class" +
        (className?.let { " '$it'" } ?: "") +
        ". Create a runtime with MapRuntimeOptions(cacheFile = ...)."
    )
}

internal fun applicationIdFromClassName(className: String): String? =
  className.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

internal fun mainClassName(): String? = mainClassNameFromStackTraces(Thread.getAllStackTraces())

internal fun mainClassNameFromStackTraces(traces: Map<Thread, Array<StackTraceElement>>): String? {
  val preferred = traces.entries.firstOrNull { it.key.name == "main" }
  val stacks = buildList {
    if (preferred != null) add(preferred.value)
    for ((thread, frames) in traces) {
      if (thread !== preferred?.key) add(frames)
    }
  }
  for (frames in stacks) {
    mainClassNameFromFrames(frames.asList())?.let {
      return it
    }
  }
  return null
}

internal fun mainClassNameFromFrames(frames: List<StackTraceElement>): String? =
  frames
    .asReversed()
    .firstOrNull { frame -> frame.methodName == "main" && isApplicationClass(frame.className) }
    ?.className

internal fun isApplicationClass(className: String): Boolean = LAUNCHER_PREFIXES.none {
  className.startsWith(it)
}

private val LAUNCHER_PREFIXES =
  arrayOf(
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.sun.",
    "kotlin.",
    "kotlinx.",
    "org.junit.",
    "org.gradle.",
    "worker.org.gradle.",
    "com.intellij.rt.",
  )
