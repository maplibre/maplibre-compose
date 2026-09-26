import { TrendChart, type ChartSpec, type Timeline } from "./chart";
import { icon, type icons } from "./icons";
import { define, definitions, installTooltips, term } from "./terms";
import { Tree } from "./tree";
import {
  declaration,
  format,
  formatDate,
  formatDelta,
  isRelease,
  newTab,
  repository,
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

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Partial<HTMLElementTagNameMap[K]> = {},
  ...children: (Node | string)[]
) {
  const element = Object.assign(document.createElement(tag), props);
  element.append(...children);
  return element;
}

function commitLink(commit: string) {
  return el("a", { ...newTab, className: "metrics-sha", href: `${repository}/commit/${commit}`, textContent: commit.slice(0, 7) });
}

function releaseLink(tag: string) {
  return el("a", { ...newTab, href: `${repository}/releases/tag/${tag}`, textContent: tag });
}

function moduleName(module: string) {
  return module.split("/").at(-1)!;
}

async function fetchJson<T>(url: URL): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${response.status} ${url}`);
  return response.json();
}

export async function start() {
  const root = document.querySelector<HTMLElement>(".metrics")!;
  const base = new URL(root.dataset.source!, location.href);
  const params = new URLSearchParams(location.search);

  let index: Index;
  try {
    index = await fetchJson<Index>(new URL("index.json", base));
  } catch {
    $("metrics-status").textContent = "This build has no metrics data.";
    return;
  }
  const { commits } = index;
  const last = commits.length - 1;
  const defs = definitions(index.thresholds);
  installTooltips(root);

  let group: Group = (["library", "demo", "all"] as const).find((g) => g === params.get("code")) ?? "library";
  let module: string | null = params.get("module");
  let series: Series = {};
  let selected = Math.max(0, commits.findIndex((c) => c.commit === params.get("commit")));
  if (!params.get("commit")) selected = last;
  let hovered: number | null = null;
  let scopeLoad = 0;
  let detailLoad = 0;

  const timeline: Timeline = {
    commits,
    times: commits.map((c) => Date.parse(c.date)),
    releases: commits.flatMap((c, i) => c.tags.filter(isRelease).map((label) => ({ index: i, label }))),
    hover(i) {
      hovered = i;
      trendCharts.forEach((chart) => chart.setCursor(hovered, selected));
    },
    select(i) {
      if (i === selected) return;
      selected = i;
      showSelection();
      void loadDetail();
    },
  };
  const specs = charts(index, defs);
  const trendCharts = specs.map((spec) => new TrendChart(spec, timeline));
  $("metrics-charts").append(...trendCharts.map((chart) => chart.element));

  const tree = new Tree($("metrics-tree"), defs);
  const scope = () =>
    index.scopes.find((s) => (module ? s.module === module : s.module === null && s.group === group))!;

  function saveUrl() {
    const url = new URL(location.href);
    const values = { code: group === "library" ? null : group, module, commit: selected === last ? null : commits[selected].commit };
    for (const [key, value] of Object.entries(values)) {
      if (value) url.searchParams.set(key, value);
      else url.searchParams.delete(key);
    }
    history.replaceState(null, "", url);
  }

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

  const releaseIndices = commits.flatMap((c, i) => (c.tags.some(isRelease) ? [i] : []));
  const releaseOf = (i: number) => commits[i].tags.find(isRelease);
  const navigation: {
    id: string;
    icon: keyof typeof icons;
    target(): number | null;
    label(target: number | null): string;
  }[] = [
    {
      id: "metrics-previous-release",
      icon: "skipPrevious",
      target: () => releaseIndices.findLast((i) => i < selected) ?? null,
      label: (i) => (i == null ? "No earlier release" : `Previous release: ${releaseOf(i)}`),
    },
    {
      id: "metrics-previous",
      icon: "chevronLeft",
      target: () => (selected > 0 ? selected - 1 : null),
      label: (i) => (i == null ? "No earlier commit" : "Previous commit"),
    },
    {
      id: "metrics-next",
      icon: "chevronRight",
      target: () => (selected < last ? selected + 1 : null),
      label: (i) => (i == null ? "No later commit" : "Next commit"),
    },
    {
      id: "metrics-next-release",
      icon: "skipNext",
      // Past the last release, this goes to the latest commit.
      target: () => releaseIndices.find((i) => i > selected) ?? (selected < last ? last : null),
      label: (i) =>
        i == null ? "No later commit" : releaseOf(i) ? `Next release: ${releaseOf(i)}` : "Latest commit",
    },
  ];
  for (const nav of navigation) {
    const button = $<HTMLButtonElement>(nav.id);
    button.append(icon(nav.icon));
    button.addEventListener("click", () => {
      const target = nav.target();
      if (target != null) timeline.select(target);
    });
  }

  /** The release before [i] that the tiles compare against, or the first commit. */
  function baseline(i: number) {
    for (let j = i - 1; j >= 0; j--) {
      const release = commits[j].tags.find(isRelease);
      if (release) return { index: j, label: `since ${release}` };
    }
    return { index: 0, label: `since ${formatDate(commits[0].date)}` };
  }

  function showSelection() {
    const commit = commits[selected];
    const release = commit.tags.find(isRelease);
    $("metrics-selection-label").replaceChildren(
      ...(release ? ["Release ", releaseLink(release)] : [selected === last ? "Latest commit" : "Selected commit"]),
      `, ${formatDate(commit.date)}`,
    );
    $("metrics-selection-commit").replaceChildren(commitLink(commit.commit));
    $("metrics-selection-title").textContent = commit.title;
    for (const nav of navigation) {
      const button = $<HTMLButtonElement>(nav.id);
      const target = nav.target();
      button.disabled = target == null;
      button.ariaLabel = nav.label(target);
      define(button, button.ariaLabel);
    }

    const { index: before, label } = baseline(selected);
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
    trendCharts.forEach((chart) => chart.setCursor(hovered, selected));
    saveUrl();
  }

  async function loadScope() {
    showControls();
    const load = ++scopeLoad;
    root.classList.add("metrics-loading");
    try {
      const next = await fetchJson<Series>(new URL(`series/${scope().id}.json`, base));
      if (load !== scopeLoad) return;
      series = next;
      $("metrics-status").hidden = true;
      $("metrics-body").hidden = false;
      trendCharts.forEach((chart, i) =>
        chart.setData(specs[i].series.map((s) => series[s.key] ?? [])),
      );
      showSelection();
      await loadDetail();
    } catch {
      if (load !== scopeLoad) return;
      $("metrics-status").hidden = false;
      $("metrics-status").textContent = "Couldn't load metrics data.";
    } finally {
      if (load === scopeLoad) root.classList.remove("metrics-loading");
    }
  }

  let report: CommitReport | undefined;
  async function loadDetail() {
    const load = ++detailLoad;
    const commit = commits[selected].commit;
    const status = $("metrics-detail-status");
    $("metrics-detail").classList.add("metrics-stale");
    try {
      // One file holds every scope, so changing scope reuses it.
      if (report?.commit !== commit) {
        const next = await fetchJson<CommitReport>(new URL(`snapshots/${commit}.json`, base));
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
