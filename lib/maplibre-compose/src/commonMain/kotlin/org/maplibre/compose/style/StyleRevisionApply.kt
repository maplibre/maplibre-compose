package org.maplibre.compose.style

/**
 * Applies [revision] through [applier] and publishes the composition's live layer snapshot. The
 * composition tree records desired state only; the caller is the writer.
 */
internal fun applyStyleRevision(node: StyleNode) {
  applyStyleRevision(node, node.snapshotRevision(), node.revisionApplier)
}

internal fun applyStyleRevision(
  node: StyleNode,
  revision: DesiredStyleRevision,
  applier: StyleApplier,
) {
  node.prepareBaseStyle()
  applier.apply(
    binding = node.binding,
    revision = revision,
    baseStyle = node.baseStyle(),
    imageManager = node.imageManager,
    refreshSource = { node.sourceManager.sources?.refreshSource(it) },
    reportError = node.reportError,
    logger = node.logger,
  )
  node.publishAfterApply(revision)
}
