import { format, newTab, sourceUrl, type FileReport, type ModuleReport, type Package } from "./model";
import { icon } from "./icons";
import { define, type definitions } from "./terms";

type Definitions = ReturnType<typeof definitions>;

interface Dependencies {
  imports: string[];
  importedBy: string[];
  instability: number;
  inCycle: boolean;
}

interface TreeNode {
  id: string;
  name: string;
  detail?: string;
  href?: string;
  loc: number;
  functions: number;
  cognitive: number;
  dependencies?: Dependencies;
  children: TreeNode[];
}

interface Column {
  key: string;
  label: string;
  definition?: keyof Definitions;
  /** The sort value; rows without one sort last. */
  value(node: TreeNode): number;
  text(node: TreeNode): string;
  /** Detail shown on hover, such as the packages behind a count. */
  title?(node: TreeNode): string | undefined;
}

const columns: Column[] = [
  { key: "loc", label: "Lines", definition: "lines", value: (n) => n.loc, text: (n) => format(n.loc) },
  {
    key: "functions",
    label: "Functions",
    value: (n) => n.functions,
    text: (n) => format(n.functions),
  },
  {
    key: "cognitive",
    label: "Complexity",
    definition: "cognitiveTotal",
    value: (n) => n.cognitive,
    text: (n) => format(n.cognitive),
  },
  {
    key: "perFunction",
    label: "Per function",
    value: (n) => (n.functions ? n.cognitive / n.functions : -1),
    text: (n) => (n.functions ? (n.cognitive / n.functions).toFixed(1) : "–"),
  },
  {
    key: "imports",
    label: "Imports",
    definition: "imports",
    value: (n) => n.dependencies?.imports.length ?? -1,
    text: (n) => (n.dependencies ? format(n.dependencies.imports.length) : ""),
    title: (n) => n.dependencies?.imports.join(", "),
  },
  {
    key: "importedBy",
    label: "Imported by",
    definition: "importedBy",
    value: (n) => n.dependencies?.importedBy.length ?? -1,
    text: (n) => (n.dependencies ? format(n.dependencies.importedBy.length) : ""),
    title: (n) => n.dependencies?.importedBy.join(", "),
  },
  {
    key: "instability",
    label: "Instability",
    definition: "instability",
    value: (n) => n.dependencies?.instability ?? -1,
    text: (n) => n.dependencies?.instability.toFixed(2) ?? "",
  },
];

function byKey<T>(items: T[], key: (item: T) => string) {
  const groups = new Map<string, T[]>();
  for (const item of items) groups.set(key(item), [...(groups.get(key(item)) ?? []), item]);
  return [...groups];
}

function group(id: string, name: string, children: TreeNode[]): TreeNode {
  return {
    id,
    name,
    loc: children.reduce((sum, c) => sum + c.loc, 0),
    functions: children.reduce((sum, c) => sum + c.functions, 0),
    cognitive: children.reduce((sum, c) => sum + c.cognitive, 0),
    children,
  };
}

/** Modules, packages, and files, each summing the files beneath it. */
function build(
  files: FileReport[],
  packages: Package[],
  modules: ModuleReport[],
  cycles: string[][],
  commit: string,
  byModule: boolean,
) {
  const moduleName = (module: string) => module.split("/").at(-1)!;
  const moduleReports = new Map(modules.map((m) => [m.name, m]));
  const inCycle = new Set(cycles.flat());
  const reports = new Map(packages.map((p) => [p.name, p]));
  const modulesOf = new Map(byKey(files, (f) => f.packageName).map(([name, fs]) => [name, new Set(fs.map((f) => f.module))]));

  const fileNode = (f: FileReport): TreeNode => ({
    id: f.path,
    name: f.path.split("/").at(-1)!,
    detail: f.sourceSet,
    href: sourceUrl(commit, f.path),
    loc: f.loc,
    functions: f.functions,
    cognitive: f.cognitiveComplexity,
    children: [],
  });
  const packageNodes = (scope: string, items: FileReport[]) =>
    byKey(items, (f) => f.packageName).map(([name, members]) => {
      const node = group(`${scope}#${name}`, name || "(default package)", members.map(fileNode));
      const report = reports.get(name);
      if (report)
        node.dependencies = {
          imports: report.dependsOn,
          importedBy: report.dependedOnBy,
          instability: report.instability,
          inCycle: inCycle.has(name),
        };
      // The package graph is by name, so a package split across modules reports as one.
      const others = [...(modulesOf.get(name) ?? [])].filter((m) => m !== scope);
      if (byModule && others.length) node.detail = `also in ${others.map(moduleName).join(", ")}`;
      return node;
    });

  if (!byModule) return packageNodes("", files);
  return byKey(files, (f) => f.module).map(([module, members]) => {
    const node = group(module, moduleName(module), packageNodes(module, members));
    const report = moduleReports.get(module);
    if (report)
      node.dependencies = {
        imports: report.dependsOn.map(moduleName),
        importedBy: report.dependedOnBy.map(moduleName),
        instability: report.instability,
        inCycle: false,
      };
    return node;
  });
}

const el = <K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Partial<HTMLElementTagNameMap[K]> = {},
  ...children: (Node | string)[]
) => {
  const element = Object.assign(document.createElement(tag), props);
  element.append(...children);
  return element;
};

/** Modules, packages, and files that expand in place, shaded by their share of their parent's lines. */
export class Tree {
  private readonly expanded = new Set<string>();
  private sort = "loc";
  private roots: TreeNode[] = [];

  constructor(
    private readonly host: HTMLElement,
    private readonly definitions: Definitions,
  ) {}

  show(
    files: FileReport[],
    packages: Package[],
    modules: ModuleReport[],
    cycles: string[][],
    commit: string,
    byModule: boolean,
  ) {
    this.roots = build(files, packages, modules, cycles, commit, byModule);
    this.render();
  }

  private render() {
    const sortColumn = columns.find((c) => c.key === this.sort)!;
    const head = el(
      "div",
      { className: "metrics-tree-head" },
      el("span", { textContent: "Name" }),
      ...columns.map((column) => {
        const cell = el(
          "span",
          {},
          this.sortButton(column),
        );
        if (column === sortColumn) cell.setAttribute("aria-sort", "descending");
        return cell;
      }),
    );
    head.setAttribute("role", "row");
    for (const cell of head.children) cell.setAttribute("role", "columnheader");

    const rows: HTMLElement[] = [];
    const visit = (nodes: TreeNode[], depth: number, parentLoc: number) => {
      const sorted = [...nodes].sort(
        (a, b) => sortColumn.value(b) - sortColumn.value(a) || a.name.localeCompare(b.name),
      );
      for (const node of sorted) {
        rows.push(this.row(node, depth, parentLoc));
        if (this.expanded.has(node.id)) visit(node.children, depth + 1, node.loc);
      }
    };
    visit(this.roots, 0, this.roots.reduce((sum, n) => sum + n.loc, 0));
    this.host.replaceChildren(head, ...rows);
  }

  private sortButton(column: Column) {
    const button = el("button", {
      className: "metrics-sort",
      textContent: column.label,
      onclick: () => {
        this.sort = column.key;
        this.render();
      },
    });
    return column.definition ? define(button, this.definitions[column.definition]) : button;
  }

  private row(node: TreeNode, depth: number, parentLoc: number) {
    const open = this.expanded.has(node.id);
    const name = node.children.length
      ? el("button", {
          className: "metrics-toggle",
          textContent: node.name,
          onclick: () => {
            if (open) this.expanded.delete(node.id);
            else this.expanded.add(node.id);
            this.render();
          },
        })
      : el("span", { className: "metrics-file", textContent: node.name });
    const nameCell = el("span", { className: "metrics-tree-name" }, name);
    nameCell.style.paddingLeft = `${depth * 1.25}rem`;
    if (node.href) {
      const link = el("a", { ...newTab, className: "metrics-icon-link", href: node.href, ariaLabel: `${node.name} on GitHub` });
      link.append(icon("openInNew"));
      nameCell.append(link);
    }
    if (node.dependencies?.inCycle) {
      const marker = define(el("span", { className: "metrics-cycle", ariaLabel: "In a dependency cycle" }), this.definitions.cycle);
      marker.setAttribute("role", "img");
      marker.append(icon("cycle"));
      nameCell.append(marker);
    }
    if (node.detail) nameCell.append(el("span", { className: "metrics-muted", textContent: node.detail }));

    const row = el(
      "div",
      { className: `metrics-tree-row metrics-depth-${Math.min(depth, 2)}` },
      nameCell,
      ...columns.map((column) =>
        {
          const cell = el("span", { textContent: column.text(node) });
          const list = column.title?.(node);
          return list ? define(cell, list) : cell;
        },
      ),
    );
    row.style.setProperty("--weight", `${parentLoc ? (node.loc / parentLoc) * 100 : 0}%`);
    row.setAttribute("role", "row");
    row.setAttribute("aria-level", String(depth + 1));
    if (node.children.length) row.setAttribute("aria-expanded", String(open));
    for (const cell of row.children) cell.setAttribute("role", "gridcell");
    return row;
  }
}
