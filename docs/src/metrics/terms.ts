import type { Index } from "./model";

/** Definitions for the jargon on the page, shown on hover or focus. */
export function definitions(thresholds: Index["thresholds"]) {
  return {
    lines:
      "Physical lines, including comments and blank lines. Shading shows each entry's share of its parent's lines.",
    cognitive:
      "Detekt's cognitive complexity: breaks in linear control flow, weighted by nesting. " +
      `Its CognitiveComplexMethod rule reports functions over ${thresholds.cognitiveComplexMethod}.`,
    cognitiveTotal:
      "Cognitive complexity summed over the files: breaks in linear control flow, weighted by nesting.",
    cyclomatic:
      "One plus each branch, loop, catch, break or continue, &&, ||, ?:, and call to a scope function " +
      `such as let or forEach. Detekt's CyclomaticComplexMethod rule reports functions over ${thresholds.cyclomaticComplexMethod}.`,
    complexFunctions:
      `Functions with cognitive complexity over ${thresholds.cognitiveComplexMethod}, ` +
      "which Detekt's CognitiveComplexMethod rule reports.",
    longFunctions:
      `Functions with more than ${thresholds.longMethod} lines of code, which Detekt's LongMethod rule reports.`,
    cycle: "Packages that reach each other by following imports.",
    imports:
      "What this imports, by its import statements: packages for a package, modules for a module. " +
      "Only the selected code counts.",
    importedBy: "The packages or modules in the selected code that import this one.",
    instability:
      "Imports divided by imports plus importers. 0 means it is only imported; 1 means it only imports.",
  };
}

/** Marks [element] as a defined term. */
export function define<T extends HTMLElement>(element: T, definition: string): T {
  element.dataset.definition = definition;
  element.classList.add("metrics-term");
  if (element.tabIndex < 0) element.tabIndex = 0;
  return element;
}

export function term(text: string, definition: string) {
  return define(Object.assign(document.createElement("span"), { textContent: text }), definition);
}

/** Shows one shared tooltip for the defined terms under [root]. */
export function installTooltips(root: HTMLElement) {
  const tip = Object.assign(document.createElement("div"), {
    id: "metrics-term-tip",
    className: "metrics-term-tip",
    role: "tooltip",
    hidden: true,
  });
  document.body.append(tip);
  let current: HTMLElement | null = null;

  const show = (target: HTMLElement) => {
    current?.removeAttribute("aria-describedby");
    current = target;
    tip.textContent = target.dataset.definition!;
    tip.hidden = false;
    target.setAttribute("aria-describedby", tip.id);
    const box = target.getBoundingClientRect();
    const width = tip.offsetWidth;
    const left = Math.min(Math.max(8, box.left), document.documentElement.clientWidth - width - 8);
    tip.style.left = `${left + scrollX}px`;
    tip.style.top = `${box.bottom + scrollY + 6}px`;
  };
  const hide = () => {
    current?.removeAttribute("aria-describedby");
    current = null;
    tip.hidden = true;
  };
  const termOf = (target: EventTarget | null) =>
    target instanceof Element ? target.closest<HTMLElement>("[data-definition]") : null;

  root.addEventListener("pointerover", (event) => {
    const target = termOf(event.target);
    if (target && target !== current) show(target);
  });
  root.addEventListener("pointerout", (event) => {
    // A tooltip opened by keyboard focus stays until focus moves.
    if (current && current !== document.activeElement && !current.contains(event.relatedTarget as Node | null))
      hide();
  });
  root.addEventListener("focusin", (event) => {
    const target = termOf(event.target);
    if (target) show(target);
  });
  root.addEventListener("focusout", hide);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") hide();
  });
}
