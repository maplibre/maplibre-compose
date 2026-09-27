import { TrendChart, type ChartSpec } from "./chart";
import { $, el, fetchJson, setSearchParams, showBody } from "./page";
import { CommitSelection } from "./selection";
import { definitions, installTooltips, term } from "./terms";
import { Tree } from "./tree";
import {
  declaration,
  format,
  formatDelta,
  moduleName,
  newTab,
  sourceUrl,
  type FileReport,
  type Index,
  type Ranked,
  type Scope,
  type Series,
  type CommitReport,
  type ScopeReport,
} from "./model";

type Group = Scope["group"];
type Definitions = ReturnType<typeof definitions>;

interface Tile {
  key: string;
  label: string;
  definition?: keyof Definitions;
  note?: (index: Index, series: Series, i: number) => string;
}

const tiles: Tile[] = [
  { key: "loc", label: "Production code", note: () => "lines" },
  { key: "testLoc", label: "Test code", note: () => "lines" },
  { key: "functions", label: "Functions" },
  {
    key: "cognitiveComplexMethods",
    label: "Complex functions",
    definition: "complexFunctions",
    note: (index) => `cognitive > ${index.thresholds.cognitiveComplexMethod}`,
  },
  {
    key: "longMethods",
    label: "Long functions",
    definition: "longFunctions",
    note: (index) => `over ${index.thresholds.longMethod} lines`,
  },
  {
    key: "packagesInCycles",
    label: "Packages in cycles",
    definition: "cycle",
    note: (_, series, i) => `of ${format(series.packages?.[i])} packages`,
  },
];

function charts(index: Index, d: Definitions): ChartSpec[] {
  const t = index.thresholds;
  return [
    {
      title: "Code size",
      unit: "lines",
      series: [
        { key: "loc", label: "Production" },
        { key: "testLoc", label: "Tests" },
      ],
    },
    {
      title: "Functions over Detekt thresholds",
      unit: "functions",
      series: [
        { key: "cognitiveComplexMethods", label: `Cognitive > ${t.cognitiveComplexMethod}`, definition: d.cognitive },
        { key: "cyclomaticComplexMethods", label: `Cyclomatic > ${t.cyclomaticComplexMethod}`, definition: d.cyclomatic },
        { key: "longMethods", label: `Length > ${t.longMethod}`, definition: d.longFunctions },
      ],
    },
    {
      title: "Function cognitive complexity",
      definition: d.cognitive,
      unit: "score",
      series: [
        { key: "functionCognitiveComplexity.p90", label: "p90" },
        { key: "functionCognitiveComplexity.p99", label: "p99" },
      ],
    },
    {
      title: "Function length",
      unit: "lines of code",
      series: [
        { key: "functionLines.p90", label: "p90" },
        { key: "functionLines.p99", label: "p99" },
      ],
    },
    {
      title: "File length",
      unit: "lines",
      series: [
        { key: "fileLoc.p90", label: "p90" },
        { key: "fileLoc.p99", label: "p99" },
      ],
    },
    {
      title: "Packages in dependency cycles",
      definition: d.cycle,
      unit: "packages",
      series: [{ key: "packagesInCycles", label: "Packages" }],
    },
  ];
}

export async function start() {
  const root = document.querySelector<HTMLElement>(".metrics")!;
  const base = new URL(root.dataset.source!, location.href);
  const params = new URLSearchParams(location.search);

  let index: Index;
  try {
    index = await fetchJson<Index>(new URL("index.json", base));
  } catch {
    showBody("This build has no metrics data.");
    return;
  }
  const { commits } = index;
  // Cached series and snapshots stay valid until a new generation, or a new commit for series.
  const version = index.generation ?? 0;
  const defs = definitions(index.thresholds);
  installTooltips(root);

  let group: Group = (["library", "demo", "all"] as const).find((g) => g === params.get("code")) ?? "library";
  let module: string | null = params.get("module");
  let series: Series = {};
  let scopeLoad = 0;
  let detailLoad = 0;

  const selection = new CommitSelection("commit", () => {
    showTiles();
    void loadDetail();
  });
  selection.setCommits(commits);
  const specs = charts(index, defs);
  selection.charts = specs.map((spec) => new TrendChart(spec, selection.timeline));
  $("metrics-charts").append(...selection.charts.map((chart) => chart.element));

  const tree = new Tree($("metrics-tree"), defs);
  const scope = () =>
    index.scopes.find((s) => (module ? s.module === module : s.module === null && s.group === group))!;

  // Scope controls.
  const segments = [...document.querySelectorAll<HTMLButtonElement>(".metrics-segmented button")];
  const moduleSelect = $<HTMLSelectElement>("metrics-module");
  function showControls() {
    for (const button of segments) button.setAttribute("aria-checked", String(button.dataset.group === group));
    const modules = index.scopes
      .filter((s) => s.module && (group === "all" || s.group === group))
      .map((s) => s.module!)
      .sort();
    if (module && !modules.includes(module)) module = null;
    moduleSelect.replaceChildren(
      el("option", { value: "", textContent: "All modules" }),
      ...modules.map((m) => el("option", { value: m, textContent: m })),
    );
    moduleSelect.value = module ?? "";
  }
  for (const button of segments)
    button.addEventListener("click", () => {
      group = button.dataset.group as Group;
      void loadScope();
    });
  moduleSelect.addEventListener("change", () => {
    module = moduleSelect.value || null;
    void loadScope();
  });

  function showTiles() {
    const { selected } = selection;
    const { index: before, label } = selection.baseline();
    $("metrics-tiles").replaceChildren(
      ...tiles.map((tile) => {
        const column = series[tile.key] ?? [];
        const value = column[selected];
        const previous = column[before];
        return el(
          "div",
          { className: "metrics-tile" },
          el(
            "div",
            { className: "metrics-tile-label" },
            tile.definition ? term(tile.label, defs[tile.definition]) : tile.label,
          ),
          el("div", { className: "metrics-tile-value", textContent: format(value) }),
          el("div", { className: "metrics-muted", textContent: tile.note?.(index, series, selected) ?? " " }),
          el("div", {
            className: "metrics-tile-delta",
            textContent: value != null && previous != null && selected > 0 ? `${formatDelta(value - previous)} ${label}` : " ",
          }),
        );
      }),
    );
  }

  async function loadScope() {
    showControls();
    setSearchParams({ code: group === "library" ? null : group, module });
    const load = ++scopeLoad;
    root.classList.add("metrics-loading");
    try {
      const next = await fetchJson<Series>(new URL(`series/${scope().id}.json?v=${version}.${commits.length}`, base));
      if (load !== scopeLoad) return;
      series = next;
      showBody(null);
      selection.charts.forEach((chart, i) => chart.setData(specs[i].series.map((s) => series[s.key] ?? [])));
      selection.show();
      showTiles();
      await loadDetail();
    } catch {
      if (load !== scopeLoad) return;
      showBody("Couldn't load metrics data.");
    } finally {
      if (load === scopeLoad) root.classList.remove("metrics-loading");
    }
  }

  let report: CommitReport | undefined;
  async function loadDetail() {
    const load = ++detailLoad;
    const commit = commits[selection.selected].commit;
    const status = $("metrics-detail-status");
    $("metrics-detail").classList.add("metrics-stale");
    try {
      // One file holds every scope, so changing scope reuses it.
      if (report?.commit !== commit) {
        const next = await fetchJson<CommitReport>(new URL(`snapshots/${commit}.json?v=${version}`, base));
        if (load !== detailLoad) return;
        report = next;
      }
    } catch {
      if (load !== detailLoad) return;
      status.textContent = "Couldn't load this commit's data.";
      $("metrics-detail").hidden = true;
      return;
    }
    const scopeReport = report.scopes[scope().id];
    status.textContent = scopeReport ? "" : `Not present at ${commit.slice(0, 7)}.`;
    $("metrics-detail").hidden = !scopeReport;
    $("metrics-detail").classList.remove("metrics-stale");
    if (!scopeReport) return;
    showHotspots(commit, scopeReport);
    showTree(commit, scopeReport, report.files);
  }

  function showHotspots(commit: string, snapshot: ScopeReport) {
    const list = (title: string, measure: string | Node, ranked: Ranked[], functions: boolean) => {
      const items = ranked.slice(0, 10).map((entry) => {
        const d = declaration(entry.name);
        const context = [module ? null : moduleName(d.module), d.sourceSet, functions ? d.file : null];
        return el(
          "li",
          {},
          el("span", { className: "metrics-rank-value", textContent: format(entry.value) }),
          el(
            "span",
            {},
            el("a", { ...newTab, href: sourceUrl(commit, d.path, d.line), textContent: d.name }),
            el("span", { className: "metrics-muted", textContent: context.filter(Boolean).join(" · ") }),
          ),
        );
      });
      return el(
        "section",
        { className: "metrics-hotspot" },
        el("h3", { textContent: title }),
        el("p", { className: "metrics-muted" }, measure),
        el("ol", {}, ...items),
      );
    };
    const { largest } = snapshot;
    $("metrics-hotspots").replaceChildren(
      list(
        "Most complex functions",
        term("cognitive complexity", defs.cognitive),
        largest.functionsByCognitiveComplexity,
        true,
      ),
      list("Longest functions", "lines of code", largest.functionsByLines, true),
      list("Largest files", "lines", largest.filesByLoc, false),
    );
  }

  function showTree(commit: string, snapshot: ScopeReport, files: FileReport[]) {
    const modules = new Set(
      index.scopes.filter((s) => s.module && (group === "all" || s.group === group)).map((s) => s.module),
    );
    const inScope = files.filter((f) => (module ? f.module === module : modules.has(f.module)));
    const { packages, modules: moduleReports, packageGraph } = snapshot;
    tree.show(inScope, packages, moduleReports, packageGraph.cycles, commit, !module);
    $("structure").textContent = module ? `Packages in ${moduleName(module)}` : "Modules";
  }

  await loadScope();
}
