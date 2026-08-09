import fs from "node:fs";
import { visit } from "unist-util-visit";

/**
 * Substitutes `{{name}}` in code blocks with a version from `generate-versions`.
 *
 * A code block is literal text that no MDX expression reaches. Prose has no
 * such problem and imports `generated/versions.json` directly.
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
