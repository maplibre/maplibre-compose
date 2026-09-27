import { TrendChart, type Band, type ChartSpec, type Timeline } from "../metrics/chart";
import { icon, type icons } from "../metrics/icons";
import { formatDate, isRelease, newTab, repository, type Commit } from "../metrics/model";
import { define, installTooltips, term } from "../metrics/terms";
import { formatPercent, formatValue, type Index, type Kind, type Scope, type Series } from "./model";

const definitions = {
  cpuPerOperation:
    "Process CPU time in the measured window, on every thread, divided by the operations the workload " +
    "submitted: per frame for frame-driven workloads, per update otherwise.",
  cpuPerSecond: "Process CPU time per second of the measured window, on every thread.",
  completion:
    "Time from submitting a change until a rendered-feature query, or the style-ready event, observes it.",
  frameInterval: "Time between consecutive frame callbacks in the measured window.",
  startup:
    "Time from creating the map until its first fully rendered frame. The style, tiles, and glyphs " +
    "are packaged with the app.",
  classic:
    "The platform's classic MapLibre SDK running the same workload on the same device, back to back " +
    "with MapLibre Compose.",
  spread: "The band spans the lowest and highest repetition; the line is the median.",
  uiFrame: "Duration of the app window's frames in the measured window, from the platform's frame timing.",
  mapReturn:
    "Time from creating a map with 256 declared layers and 64 images until its content renders and " +
    "settles, with a 300 ms panel transition running at the same time.",
};

interface Metric {
  key: string;
  title: string;
  definition: string;
}

const metrics = {
  cpuPerFrame: { key: "cpu_ms_per_operation", title: "CPU per frame", definition: definitions.cpuPerOperation },
  cpuPerUpdate: { key: "cpu_ms_per_operation", title: "CPU per update", definition: definitions.cpuPerOperation },
  cpuPerSecond: { key: "cpu_ms_per_second", title: "Idle CPU per second", definition: definitions.cpuPerSecond },
  completion: { key: "completion_p50_ms", title: "Completion, median", definition: definitions.completion },
  frameInterval: { key: "frame_interval_p95_ms", title: "Frame interval p95", definition: definitions.frameInterval },
  startup: { key: "startup_first_frame_ms", title: "Time to first frame", definition: definitions.startup },
  uiFrameP95: { key: "ui_frame_p95_ms", title: "Window frame p95", definition: definitions.uiFrame },
  uiFrameMax: { key: "ui_frame_max_ms", title: "Worst window frame", definition: definitions.uiFrame },
  returnTime: { key: "completion_p50_ms", title: "Map return time, median", definition: definitions.mapReturn },
} satisfies Record<string, Metric>;

const frameDriven = ["camera", "overlays", "padding", "resize", "recompose"];
const updates = ["source", "source-latency", "layers", "layout", "paint", "sparse-paint", "style", "images", "image-burst"];

/**
 * Sections group charts by metric, so every chart in a section shares a unit and a meaning and
 * the case is the chart's title. An item's metric can list alternatives: the first one the device
 * reports is charted, so Android shows window frames where others show the frame interval.
 */
const sections: { title: string; items: { workloads: string[]; metric: Metric | Metric[] }[] }[] = [
  { title: "CPU per frame", items: [{ workloads: frameDriven, metric: metrics.cpuPerFrame }] },
  {
    title: "Frame pacing",
    items: [{ workloads: [...frameDriven, "sparse-paint"], metric: [metrics.uiFrameP95, metrics.frameInterval] }],
  },
  { title: "CPU per update", items: [{ workloads: updates, metric: metrics.cpuPerUpdate }] },
  { title: "Completion latency", items: [{ workloads: ["source-latency", "layers", "layout", "style"], metric: metrics.completion }] },
  {
    title: "Startup and idle",
    items: [
      { workloads: ["idle"], metric: metrics.startup },
      { workloads: ["map-return"], metric: metrics.returnTime },
      { workloads: ["map-return"], metric: metrics.uiFrameMax },
      { workloads: ["idle"], metric: metrics.cpuPerSecond },
    ],
  },
];

/** Metrics the publisher records with their spread across repetitions. */
const banded = new Set([
  "cpu_ms_per_operation",
  "cpu_ms_per_second",
  "completion_p50_ms",
  "frame_interval_p95_ms",
  "ui_frame_p95_ms",
  "ui_frame_max_ms",
  "startup_first_frame_ms",
]);

interface Tile {
  label: string;
  definition: string;
  /** Alternatives; the first column the device reports is shown. */
  columns: string[];
  note: string;
}

const tiles: Tile[] = [
  { label: "Idle CPU", definition: definitions.cpuPerSecond, columns: ["idle-basemap.compose.cpu_ms_per_second"], note: "ms per second" },
  { label: "Camera frame", definition: definitions.cpuPerOperation, columns: ["camera-basemap.compose.cpu_ms_per_operation"], note: "ms of CPU per frame" },
  {
    label: "Map return",
    definition: definitions.mapReturn,
    columns: ["map-return.compose.ui_frame_max_ms", "map-return.compose.completion_p50_ms"],
    note: "ms, worst window frame",
  },
  { label: "Update latency", definition: definitions.completion, columns: ["source-completion.compose.completion_p50_ms"], note: "ms, median" },
  { label: "Startup", definition: definitions.startup, columns: ["idle-basemap.compose.startup_first_frame_ms"], note: "ms to first frame" },
];

/**
 * One device's measurements: the commits it measured, in order, and its series restricted to them.
 * Everything below works on this view, so every index has data and the shared timeline, chart,
 * and navigation code apply unchanged.
 */
interface View {
  commits: Commit[];
  series: Series;
}

function view(index: Index, series: Series): View {
  const measured = index.commits.flatMap((_, i) => (Object.values(series).some((column) => column[i] != null) ? [i] : []));
  return {
    commits: measured.map((i) => index.commits[i]),
    series: Object.fromEntries(Object.entries(series).map(([key, column]) => [key, measured.map((i) => column[i] ?? null)])),
  };
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
    $("metrics-status").textContent = "This build has no benchmark data.";
    return;
  }
  if (!index.commits.length || !index.scopes.length) {
    $("metrics-status").textContent = "No benchmarks have been published yet.";
    return;
  }
  const version = index.generation ?? 0;
  installTooltips(root);

  let scope: Scope = index.scopes.find((s) => s.id === params.get("device")) ?? index.scopes[0];
  let current: View = { commits: [], series: {} };
  let selected = 0;
  let hovered: number | null = null;
  let scopeLoad = 0;
  let trendCharts: TrendChart[] = [];

  const timeline: Timeline = {
    commits: [],
    times: [],
    releases: [],
    hover(i) {
      hovered = i;
      trendCharts.forEach((chart) => chart.setCursor(hovered, selected));
    },
    select(i) {
      if (i === selected) return;
      selected = i;
      showSelection();
    },
  };

  function saveUrl() {
    const url = new URL(location.href);
    const values = {
      device: scope === index.scopes[0] ? null : scope.id,
      commit: selected === current.commits.length - 1 ? null : current.commits[selected].commit,
    };
    for (const [key, value] of Object.entries(values)) {
      if (value) url.searchParams.set(key, value);
      else url.searchParams.delete(key);
    }
    history.replaceState(null, "", url);
  }

  const scopeSelect = $<HTMLSelectElement>("benchmarks-scope");
  scopeSelect.replaceChildren(
    ...index.scopes.map((s) => el("option", { value: s.id, textContent: `${s.label} · ${s.platform}` })),
  );
  scopeSelect.value = scope.id;
  scopeSelect.addEventListener("change", () => {
    scope = index.scopes.find((s) => s.id === scopeSelect.value)!;
    void loadScope();
  });

  const releaseIndices = () => current.commits.flatMap((c, i) => (c.tags.some(isRelease) ? [i] : []));
  const releaseOf = (i: number) => current.commits[i].tags.find(isRelease);
  const navigation: {
    id: string;
    icon: keyof typeof icons;
    target(): number | null;
    label(target: number | null): string;
  }[] = [
    {
      id: "metrics-previous-release",
      icon: "skipPrevious",
      target: () => releaseIndices().findLast((i) => i < selected) ?? null,
      label: (i) => (i == null ? "No earlier release" : `Previous release: ${releaseOf(i)}`),
    },
    {
      id: "metrics-previous",
      icon: "chevronLeft",
      target: () => (selected > 0 ? selected - 1 : null),
      label: (i) => (i == null ? "No earlier measurement" : "Previous measurement"),
    },
    {
      id: "metrics-next",
      icon: "chevronRight",
      target: () => (selected < current.commits.length - 1 ? selected + 1 : null),
      label: (i) => (i == null ? "No later measurement" : "Next measurement"),
    },
    {
      id: "metrics-next-release",
      icon: "skipNext",
      // Past the last release, this goes to the latest measurement.
      target: () => releaseIndices().find((i) => i > selected) ?? (selected < current.commits.length - 1 ? current.commits.length - 1 : null),
      label: (i) => (i == null ? "No later measurement" : releaseOf(i) ? `Next release: ${releaseOf(i)}` : "Latest measurement"),
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

  /** The release before [i] that the tiles compare against, or the first measurement. */
  function baseline(i: number) {
    for (let j = i - 1; j >= 0; j--) {
      const release = current.commits[j].tags.find(isRelease);
      if (release) return { index: j, label: `since ${release}` };
    }
    return { index: 0, label: `since ${formatDate(current.commits[0].date)}` };
  }

  function buildCharts() {
    const { series } = current;
    const hasData = (key: string) => series[key]?.some((v) => v != null) ?? false;
    trendCharts = [];
    const blocks: Node[] = [];
    for (const section of sections) {
      const charts: HTMLElement[] = [];
      for (const item of section.items) {
        for (const [id, c] of Object.entries(index.cases)) {
          if (!item.workloads.includes(c.workload)) continue;
          const metric = (Array.isArray(item.metric) ? item.metric : [item.metric]).find((m) => hasData(`${id}.compose.${m.key}`));
          if (!metric) continue;
          const kinds: Kind[] = hasData(`${id}.classic.${metric.key}`) ? ["compose", "classic"] : ["compose"];
          const spec: ChartSpec = {
            title: section.items.length > 1 ? `${c.title}: ${metric.title.toLowerCase()}` : c.title,
            unit: "ms",
            definition: `${c.description} ${metric.definition}`,
            integer: false,
            format: formatValue,
            series: kinds.map((kind) => ({
              key: `${id}.${kind}.${metric.key}`,
              label: kind === "compose" ? "MapLibre Compose" : "Classic SDK",
              definition: kind === "classic" ? definitions.classic : definitions.spread,
            })),
          };
          const bands = kinds.map((kind) =>
            banded.has(metric.key)
              ? ([series[`${id}.${kind}.${metric.key}.min`] ?? [], series[`${id}.${kind}.${metric.key}.max`] ?? []] as Band)
              : undefined,
          );
          const chart = new TrendChart(spec, timeline);
          chart.setData(spec.series.map((s) => series[s.key] ?? []), bands);
          trendCharts.push(chart);
          charts.push(chart.element);
        }
      }
      if (!charts.length) continue;
      blocks.push(
        el("section", { className: "benchmarks-section" }, el("h2", { textContent: section.title }), el("div", { className: "metrics-charts" }, ...charts)),
      );
    }
    $("metrics-charts").replaceChildren(...blocks);
  }

  function showSelection() {
    const { commits, series } = current;
    const commit = commits[selected];
    const release = commit.tags.find(isRelease);
    $("metrics-selection-label").replaceChildren(
      ...(release ? ["Release ", releaseLink(release)] : [selected === commits.length - 1 ? "Latest measurement" : "Selected commit"]),
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
        const column = tile.columns.map((key) => series[key] ?? []).find((c) => c.some((v) => v != null)) ?? [];
        const value = column[selected];
        const previous = column[before];
        const delta = value != null && previous != null && previous !== 0 && selected > 0 ? `${formatPercent((value / previous - 1) * 100)} ${label}` : " ";
        return el(
          "div",
          { className: "metrics-tile" },
          el("div", { className: "metrics-tile-label" }, term(tile.label, tile.definition)),
          el("div", { className: "metrics-tile-value", textContent: formatValue(value) }),
          el("div", { className: "metrics-muted", textContent: column === series[tile.columns[0]] ? tile.note : "ms to settle, median" }),
          el("div", { className: "metrics-tile-delta", textContent: delta }),
        );
      }),
    );
    trendCharts.forEach((chart) => chart.setCursor(hovered, selected));
    saveUrl();
  }

  async function loadScope() {
    const load = ++scopeLoad;
    root.classList.add("metrics-loading");
    try {
      const series = await fetchJson<Series>(new URL(`series/${scope.id}.json?v=${version}.${index.commits.length}`, base));
      if (load !== scopeLoad) return;
      current = view(index, series);
      timeline.commits = current.commits;
      timeline.times = current.commits.map((c) => Date.parse(c.date));
      timeline.releases = current.commits.flatMap((c, i) => c.tags.filter(isRelease).map((label) => ({ index: i, label })));
      // A link to a commit this device did not measure opens its latest measurement.
      const linked = current.commits.findIndex((c) => c.commit === params.get("commit"));
      selected = linked < 0 ? current.commits.length - 1 : linked;
      $("benchmarks-scope-note").textContent = `${current.commits.length} measured commit${current.commits.length === 1 ? "" : "s"}`;
      $("metrics-status").hidden = true;
      $("metrics-body").hidden = false;
      buildCharts();
      showSelection();
    } catch {
      if (load !== scopeLoad) return;
      $("metrics-body").hidden = true;
      $("metrics-status").hidden = false;
      $("metrics-status").textContent = "Couldn't load benchmark data.";
    } finally {
      if (load === scopeLoad) root.classList.remove("metrics-loading");
    }
  }

  await loadScope();
}
