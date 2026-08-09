import fs from "node:fs";
import { visit } from "unist-util-visit";

/**
 * Substitutes `{{name}}` in code blocks with a version from `generate-versions`.
 *
 * A page that quotes a dependency coordinate has to print the version this
 * checkout publishes, and a code block is literal text that no MDX expression
 * reaches. Write the placeholder instead:
 *
 * ````markdown
 * ```toml title="libs.versions.toml"
 * maplibre-compose = { module = "org.maplibre.compose:maplibre-compose", version = "{{release}}" }
 * ```
 * ````
 *
 * Prose is not a code block, so it interpolates the same data directly:
 *
 * ```mdx
 * import versions from "../../generated/versions.json";
 *
 * The latest release is **v{versions.release}**.
 * ```
 *
 * An unknown name fails the build rather than rendering the placeholder, so a
 * renamed version cannot reach the published site.
 */
export function remarkVersions() {
  const versions = JSON.parse(
    fs.readFileSync(new URL("../generated/versions.json", import.meta.url)),
  );

  return (tree, file) => {
    visit(tree, ["code", "inlineCode"], (node) => {
      node.value = node.value.replace(/\{\{(\w+)\}\}/g, (placeholder, name) => {
        const version = versions[name];
        if (version === undefined) {
          const known = Object.keys(versions).join(", ");
          file.fail(`unknown version ${placeholder}; known versions: ${known}`);
        }
        return version;
      });
    });
  };
}
