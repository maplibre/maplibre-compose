import { TrendChart, type ChartSpec, type Timeline } from "./chart";
import {
  declaration,
  format,
  formatDate,
  formatDelta,
  isRelease,
  repository,
  sourceUrl,
  type Index,
  type Package,
  type Ranked,
  type Scope,
  type Series,
  type Snapshot,
} from "./model";

type Group = Scope["group"];

const tiles: { key: string; label: string; note?: (index: Index, at: Series, i: number) => string }[] = [
  { key: "loc", label: "Production code", note: () => "lines" },
  { key: "testLoc", label: "Test code", note: () => "lines" },
  { key: "functions", label: "Functions" },
  {
    key: "functionCognitiveComplexity.over",
    label: "Complex functions",
    note: (index) => `cognitive score over ${index.thresholds.functionCognitiveComplexity}`,
  },
  {
    key: "functionLines.over",
    label: "Long functions",
    note: (index) => `over ${index.thresholds.functionLines} lines`,
  },
  {
    key: "packagesInCycles",
    label: "Packages in cycles",
    note: (_, series, i) => `of ${format(series.packages?.[i])} packages`,
  },
];

function charts(index: Index): ChartSpec[] {
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
        { key: "functionCognitiveComplexity.over", label: `Cognitive > ${t.functionCognitiveComplexity}` },
        { key: "functionCyclomaticComplexity.over", label: `Cyclomatic > ${t.functionCyclomaticComplexity}` },
        { key: "functionLines.over", label: `Length > ${t.functionLines}` },
      ],
    },
    {
      title: "Function cognitive complexity",
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
  $("metrics-coverage").textContent =
    `${format(commits.length)} ${index.step > 1 ? `of ${format(index.totalCommits)} ` : ""}commits, ` +
    `${formatDate(commits[0].date)} to ${formatDate(commits[last].date)}.`;

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
  const specs = charts(index);
  const trendCharts = specs.map((spec) => new TrendChart(spec, timeline));
  $("metrics-charts").append(...trendCharts.map((chart) => chart.element));

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
  $("metrics-latest").addEventListener("click", () => timeline.select(last));

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
    $("metrics-selection-label").textContent =
      (selected === last ? "Latest commit" : release ? `Release ${release}` : "Selected commit") +
      `, ${formatDate(commit.date)}`;
    const link = $<HTMLAnchorElement>("metrics-selection-commit");
    link.href = `${repository}/commit/${commit.commit}`;
    link.textContent = commit.commit.slice(0, 7);
    $("metrics-selection-title").textContent = commit.title;
    $("metrics-latest").hidden = selected === last;
    $("metrics-detail-commit").textContent =
      `At ${commit.commit.slice(0, 7)}${release ? ` (${release})` : ""}, ${formatDate(commit.date)}.`;

    const { index: before, label } = baseline(selected);
    $("metrics-tiles").replaceChildren(
      ...tiles.map((tile) => {
        const column = series[tile.key] ?? [];
        const value = column[selected];
        const previous = column[before];
        return el(
          "div",
          { className: "metrics-tile" },
          el("div", { className: "metrics-tile-label", textContent: tile.label }),
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

  async function loadDetail() {
    const load = ++detailLoad;
    const commit = commits[selected].commit;
    const status = $("metrics-detail-status");
    $("metrics-detail").classList.add("metrics-stale");
    let snapshot: Snapshot;
    try {
      snapshot = await fetchJson<Snapshot>(new URL(`snapshots/${commit}/${scope().id}.json`, base));
    } catch {
      if (load !== detailLoad) return;
      status.textContent = `Not present at ${commits[selected].commit.slice(0, 7)}.`;
      $("metrics-detail").hidden = true;
      return;
    }
    if (load !== detailLoad) return;
    status.textContent = "";
    $("metrics-detail").hidden = false;
    $("metrics-detail").classList.remove("metrics-stale");
    showHotspots(snapshot);
    showBreakdown(snapshot);
    showPackages(snapshot);
  }

  function showHotspots(snapshot: Snapshot) {
    const list = (title: string, measure: string, ranked: Ranked[], functions: boolean) => {
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
            el("a", { href: sourceUrl(snapshot.commit, d.path, d.line), textContent: d.name }),
            el("span", { className: "metrics-muted", textContent: context.filter(Boolean).join(" · ") }),
          ),
        );
      });
      return el(
        "section",
        { className: "metrics-hotspot" },
        el("h3", { textContent: title }),
        el("p", { className: "metrics-muted", textContent: measure }),
        el("ol", {}, ...items),
      );
    };
    const { largest } = snapshot;
    $("metrics-hotspots").replaceChildren(
      list("Most complex functions", "cognitive complexity", largest.functionsByCognitiveComplexity, true),
      list("Longest functions", "lines of code", largest.functionsByLines, true),
      list("Largest files", "lines", largest.filesByLoc, false),
    );
  }

  function table(
    host: HTMLTableElement,
    columns: { label: string; numeric?: boolean }[],
    rows: (Node | string)[][],
  ) {
    host.replaceChildren(
      el("thead", {}, el("tr", {}, ...columns.map((c) => el("th", { className: c.numeric ? "metrics-num" : "", textContent: c.label })))),
      el(
        "tbody",
        {},
        ...rows.map((row) =>
          el("tr", {}, ...row.map((cell, i) => el("td", { className: columns[i].numeric ? "metrics-num" : "" }, cell))),
        ),
      ),
    );
  }

  function showBreakdown(snapshot: Snapshot) {
    const columns = [
      { label: module ? "Source set" : "Module" },
      { label: "Lines", numeric: true },
      { label: "Test lines", numeric: true },
      { label: "Functions", numeric: true },
      { label: "Cognitive complexity (sum)", numeric: true },
    ];
    const groups = new Map<string, { loc: number; testLoc: number; functions: number; cognitive: number }>();
    for (const set of snapshot.sourceSets) {
      const key = module ? set.name : set.module;
      const row = groups.get(key) ?? { loc: 0, testLoc: 0, functions: 0, cognitive: 0 };
      if (set.isTest) row.testLoc += set.loc;
      else {
        row.loc += set.loc;
        row.functions += set.functions;
        row.cognitive += set.cognitiveComplexity;
      }
      groups.set(key, row);
    }
    const rows = [...groups].sort(([, a], [, b]) => b.loc - a.loc || b.testLoc - a.testLoc);
    const total = rows.reduce((sum, [, row]) => sum + row.loc, 0) || 1;
    $("breakdown").textContent = module ? `Source sets in ${moduleName(module)}` : "Modules";
    table(
      $("metrics-breakdown"),
      columns,
      rows.map(([name, row]) => {
        const label = module
          ? el("span", { textContent: name })
          : el("button", {
              className: "metrics-link-button",
              textContent: moduleName(name),
              title: name,
              onclick: () => {
                module = name;
                void loadScope();
              },
            });
        const share = el("span", { className: "metrics-share" });
        share.style.width = `${(row.loc / total) * 100}%`;
        return [
          el("span", { className: "metrics-name-cell" }, label, share),
          format(row.loc),
          row.testLoc ? format(row.testLoc) : "–",
          row.loc ? format(row.functions) : "–",
          row.loc ? format(row.cognitive) : "–",
        ];
      }),
    );
  }

  let showAllPackages = false;
  function showPackages(snapshot: Snapshot) {
    const names = snapshot.packages.map((p) => p.name);
    const prefix = commonPrefix(names);
    const short = (name: string) => (prefix && name.startsWith(prefix) ? name.slice(prefix.length) || name : name);
    const inCycle = new Set(snapshot.packageGraph.cycles.flat());

    const cycles = snapshot.packageGraph.cycles;
    $("metrics-cycles").replaceChildren(
      el(
        "p",
        {},
        cycles.length
          ? `${inCycle.size} of ${names.length} packages are in ${cycles.length === 1 ? "a dependency cycle" : `${cycles.length} dependency cycles`}.`
          : `No dependency cycles among ${names.length} packages.`,
        prefix ? ` Names are relative to ${prefix.replace(/\.$/, "")}.` : "",
      ),
      ...cycles.map((cycle) =>
        el("ul", { className: "metrics-chips" }, ...cycle.map((name) => el("li", { textContent: short(name) }))),
      ),
    );

    const sorted = [...snapshot.packages].sort((a, b) => b.loc - a.loc);
    const visible = showAllPackages ? sorted : sorted.slice(0, 10);
    table(
      $("metrics-packages"),
      [
        { label: "Package" },
        { label: "Lines", numeric: true },
        { label: "Types", numeric: true },
        { label: "Imports", numeric: true },
        { label: "Imported by", numeric: true },
        { label: "Instability", numeric: true },
      ],
      visible.map((p: Package) => [
        el(
          "span",
          { className: "metrics-name-cell" },
          el("code", { textContent: short(p.name), title: p.name }),
          inCycle.has(p.name) ? el("span", { className: "metrics-tag", textContent: "cycle" }) : "",
        ),
        format(p.loc),
        format(p.types),
        format(p.dependsOn.length),
        format(p.dependedOnBy.length),
        p.instability.toFixed(2),
      ]),
    );
    const more = $<HTMLButtonElement>("metrics-packages-more");
    more.hidden = sorted.length <= 10;
    more.textContent = showAllPackages ? "Show fewer packages" : `Show all ${sorted.length} packages`;
    more.onclick = () => {
      showAllPackages = !showAllPackages;
      showPackages(snapshot);
    };
  }

  await loadScope();
}

/** The longest dotted prefix, ending in a dot, shared by every name. */
function commonPrefix(names: string[]) {
  if (names.length < 2) return "";
  const parts = names.map((n) => n.split("."));
  const shared: string[] = [];
  for (let i = 0; parts.every((p) => i < p.length - 1 && p[i] === parts[0][i]); i++) shared.push(parts[0][i]);
  return shared.length ? `${shared.join(".")}.` : "";
}
