import { Charts } from "./charts";
import { renderDistribution } from "./distribution";
import {
  delta,
  distributionMetrics,
  metricDefinition,
  format,
  hotspotKinds,
  repository,
  sourceLink,
  type Entry,
  type History,
  type Point,
  type Scope,
  type Snapshot,
  type Summary,
} from "./model";

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const select = (id: string) => $<HTMLSelectElement>(id);
const input = (id: string) => $<HTMLInputElement>(id);
const date = (value: string) =>
  new Date(value).toLocaleDateString("en", { month: "short", day: "numeric", timeZone: "UTC" });
function node<K extends keyof HTMLElementTagNameMap>(tag: K, text = "", className = "") {
  const element = document.createElement(tag);
  element.textContent = text;
  element.className = className;
  return element;
}
function link(text: string, href: string, className = "") {
  const a = node("a", text, className);
  a.href = href;
  return a;
}
function options(control: HTMLSelectElement, entries: [string, string][], preferred = "") {
  control.replaceChildren(
    ...entries.map(([value, text]) => {
      const option = node("option", text);
      option.value = value;
      return option;
    }),
  );
  control.value = entries.some(([value]) => value === preferred)
    ? preferred
    : (entries[0]?.[0] ?? "");
}
const metricLabel = (key: string) => {
  const label = key
    .replace(/([A-Z])/g, " $1")
    .toLowerCase()
    .replace(/\b(sloc|lloc|cloc|loc|todo)\b/g, (word) => word.toUpperCase());
  return label.charAt(0).toUpperCase() + label.slice(1);
};
interface Column {
  key: string;
  label: string;
}
interface Row {
  name: string;
  label: HTMLElement;
  values: Record<string, number | string | null>;
}

export function start() {
  const base = new URL($("dashboard").dataset.source!, location.href);
  const params = new URL(location.href).searchParams;
  let history: History;
  let scope: Scope;
  let entries: Entry[] = [];
  let available: Entry[] = [];
  let summaries = new Map<string, Summary>();
  let selected: Entry;
  let baseline: Entry;
  let current: Snapshot | undefined;
  let before: Snapshot | undefined;
  let view = ["overview", "complexity", "dependencies", "size"].includes(params.get("view") ?? "")
    ? params.get("view")!
    : "overview";
  let tab = ["packages", "distributions", "hotspots", "sourceSets", "summary"].includes(
    params.get("tab") ?? "",
  )
    ? params.get("tab")!
    : "packages";
  let distributionMetric = params.get("metric") ?? "functionCognitiveComplexity";
  if (!Object.hasOwn(distributionMetrics, distributionMetric))
    distributionMetric = "functionCognitiveComplexity";
  let sort = "value";
  let descending = true;
  let scopeRequest = 0;
  let detailRequest = 0;
  let scopeLoading = false;
  const seriesCache = new Map<string, Promise<Point[]>>();
  const snapshotCache = new Map<string, Promise<Snapshot>>();
  const charts = new Charts(
    $("charts"),
    (commit) => {
      select("selected").value = commit;
      void changeSelection();
    },
    (metric) => {
      distributionMetric = metric;
      changeTab("distributions");
      $("tab-distributions").focus();
      $("breakdown").scrollIntoView({ block: "start" });
    },
  );

  function url(path: string) {
    const value = new URL(path, base);
    if (value.origin !== base.origin || !value.pathname.startsWith(base.pathname))
      throw new Error("Invalid dataset path");
    return value;
  }
  async function read<T>(path: string): Promise<T> {
    const response = await fetch(url(path));
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }
  function snapshot(entry: Entry) {
    const key = `${entry.path}${scope.id}.json`;
    if (!snapshotCache.has(key)) {
      // Keep scrubbing through history from retaining every parsed report.
      if (snapshotCache.size >= 12) snapshotCache.delete(snapshotCache.keys().next().value!);
      snapshotCache.set(
        key,
        read<Snapshot>(key)
          .then((report) => {
            if (report.commit !== entry.commit) throw new Error("Snapshot commit mismatch");
            return report;
          })
          .catch((error) => {
            snapshotCache.delete(key);
            throw error;
          }),
      );
    }
    return snapshotCache.get(key)!;
  }
  function save() {
    if (!selected || !available.length) return;
    const locationUrl = new URL(location.href);
    const values = {
      metric: distributionMetric,
      scope: select("scope").value,
      module: select("module").value,
      sourceSet: select("source-set").value,
      days: select("period").value,
      view,
      tab,
      commit: selected.commit,
      compare: input("compare").checked ? "1" : "0",
      baseline: baseline.commit,
    };
    for (const [key, value] of Object.entries(values)) {
      if (value) locationUrl.searchParams.set(key, value);
      else locationUrl.searchParams.delete(key);
    }
    window.history.replaceState(null, "", locationUrl);
  }
  function moduleOptions(preferred = select("module").value) {
    const group = select("scope").value;
    const modules = [
      ...new Set(
        history.scopes
          .filter((s) => s.module && (group === "all" || s.group === group))
          .map((s) => s.module!),
      ),
    ].sort();
    options(
      select("module"),
      [["", "All modules"], ...modules.map((module): [string, string] => [module, module])],
      preferred,
    );
  }
  function matchingScopes() {
    const module = select("module").value;
    return history.scopes.filter((s) =>
      module ? s.module === module : s.module === null && s.group === select("scope").value,
    );
  }
  function sourceSetOptions(preferred = select("source-set").value) {
    const sets = [
      ...new Set(
        matchingScopes()
          .map((s) => s.sourceSet)
          .filter((s): s is string => s !== null),
      ),
    ].sort();
    options(
      select("source-set"),
      [["", "All source sets"], ...sets.map((set): [string, string] => [set, set])],
      preferred,
    );
  }
  async function loadScope() {
    scopeLoading = true;
    for (const id of ["selected", "baseline", "compare", "period"])
      $<HTMLInputElement>(id).disabled = true;
    const version = ++scopeRequest;
    detailRequest++;
    current = undefined;
    before = undefined;
    $("detail-content").hidden = true;
    $("retry-detail").hidden = true;
    $("detail-status").textContent = "";
    charts.clear();
    charts.resetZoom();
    $("status").textContent = "Loading scope history…";
    $("status").hidden = false;
    $("retry").hidden = true;
    scope = matchingScopes().find((s) => s.sourceSet === (select("source-set").value || null))!;
    try {
      if (!scope) throw new Error("Scope unavailable");
      const key = scope.id;
      if (!seriesCache.has(key))
        seriesCache.set(
          key,
          read<Point[]>(scope.path).catch((error) => {
            seriesCache.delete(key);
            throw error;
          }),
        );
      const points = await seriesCache.get(key)!;
      if (version !== scopeRequest) return;
      summaries = new Map(points.map((p) => [p.commit, p.summary]));
      scopeLoading = false;
      for (const id of ["selected", "baseline", "compare", "period"])
        $<HTMLInputElement>(id).disabled = false;
      $("status").hidden = true;
      filterPeriod();
    } catch {
      if (version !== scopeRequest) return;
      $("status").textContent = "Could not load this scope. Retry or select another scope.";
      $("retry").hidden = false;
      // Scope filters stay enabled, so a failed series can be left without reloading.
    }
  }
  function filterPeriod() {
    charts.resetZoom();
    const latest = Math.max(...history.snapshots.map((entry) => Date.parse(entry.commitDate)));
    const days = Number(select("period").value);
    entries = history.snapshots.filter(
      (entry) => !days || Date.parse(entry.commitDate) >= latest - days * 86400000,
    );
    available = entries.filter((entry) => summaries.has(entry.commit));
    if (!available.length) {
      detailRequest++;
      charts.clear();
      current = undefined;
      before = undefined;
      for (const id of ["selected", "baseline"]) {
        options(select(id), []);
        select(id).disabled = true;
      }
      input("compare").disabled = true;
      $("download").hidden = true;
      $("commit-caption").textContent = "";
      $("detail-status").textContent = "";
      $("sample-count").textContent = "0 snapshots";
      $("detail-content").hidden = true;
      $("status").hidden = false;
      $("status").textContent =
        "No measurements for this scope in the selected period. Choose a longer period or another scope.";
      return;
    }
    for (const id of ["selected", "baseline"]) select(id).disabled = false;
    input("compare").disabled = false;
    $("download").hidden = false;
    $("status").hidden = true;
    const choices: [string, string][] = available.map((entry) => [
      entry.commit,
      `${date(entry.commitDate)} · ${entry.commit.slice(0, 7)} · ${entry.title}`,
    ]);
    options(
      select("selected"),
      choices,
      available.find((entry) => entry.commit === (selected?.commit ?? params.get("commit")))
        ?.commit ?? available.at(-1)!.commit,
    );
    options(
      select("baseline"),
      choices,
      available.find((entry) => entry.commit === (baseline?.commit ?? params.get("baseline")))
        ?.commit ?? available[0].commit,
    );
    $("sample-count").textContent =
      `${available.length} snapshots${history.step > 1 ? ` · every ${history.step} commits` : ""}`;
    const scopeText =
      scope.module ??
      { library: "Library", demo: "Demo", all: "Library + demo" }[scope.group] ??
      scope.group;
    $("scope-note").textContent = `${scopeText} / ${scope.sourceSet ?? "all source sets"}`;
    void changeSelection();
  }
  function renderCharts() {
    if (scopeLoading || !selected || !available.length) return;
    for (const button of document.querySelectorAll<HTMLButtonElement>("[data-view]"))
      button.setAttribute("aria-pressed", String(button.dataset.view === view));
    charts.render(
      entries,
      summaries,
      view,
      selected,
      input("compare").checked ? baseline : null,
      input("releases").checked,
    );
  }
  async function changeSelection() {
    selected = available.find((entry) => entry.commit === select("selected").value)!;
    baseline = available.find((entry) => entry.commit === select("baseline").value)!;
    if (!selected || !baseline) return;
    const compare = input("compare").checked;
    $("baseline-label").hidden = !compare;
    renderCharts();
    save();
    $("commit-caption").replaceChildren(
      link(selected.commit.slice(0, 8), `${repository}/commit/${selected.commit}`),
      document.createTextNode(
        ` · ${selected.title}${compare ? ` · Comparing with ${baseline.commit.slice(0, 8)}` : ""}`,
      ),
    );
    $<HTMLAnchorElement>("download").href = url(`${selected.path}${scope.id}.json`).href;
    $("detail-content").hidden = true;
    $("retry-detail").hidden = true;
    $("detail-status").textContent = "Loading snapshot…";
    const version = ++detailRequest;
    try {
      const reports = await Promise.all([
        snapshot(selected),
        ...(compare ? [snapshot(baseline)] : []),
      ]);
      if (version !== detailRequest) return;
      [current, before] = reports;
      $("detail-status").textContent = "";
      $("detail-content").hidden = false;
      renderTable();
    } catch {
      if (version !== detailRequest) return;
      $("detail-status").textContent =
        "Could not load this snapshot. Retry or select another commit.";
      $("retry-detail").hidden = false;
    }
  }
  function renderTable() {
    if (!current) return;
    const isDistribution = tab === "distributions";
    $("distribution-detail").hidden = !isDistribution;
    for (const element of document.querySelectorAll<HTMLElement>(
      ".table-tools, #table-note, .table-scroll, #cycles",
    ))
      element.hidden = isDistribution;
    if (isDistribution) {
      renderDistribution(
        $("distribution-detail"),
        current,
        before,
        distributionMetric,
        (metric) => {
          distributionMetric = metric;
          renderTable();
          save();
        },
      );
      return;
    }
    const compare = Boolean(before);
    const rows: Row[] = [];
    let columns: Column[] = [];
    $("cycles").replaceChildren();
    const comparison = (value: number, old: number | undefined) => ({
      baseline: old ?? null,
      change: old == null ? null : value - old,
    });
    if (tab === "packages") {
      columns = [
        { key: "value", label: "Lines" },
        ...(compare
          ? [
              { key: "baseline", label: "Baseline" },
              { key: "change", label: "Δ lines" },
            ]
          : []),
        { key: "types", label: "Types" },
        { key: "out", label: "Imports out" },
        { key: "in", label: "Imports in" },
        { key: "instability", label: "Instability" },
      ];
      const names = new Set(
        [...current.packages, ...(before?.packages ?? [])].map((pkg) => pkg.name),
      );
      for (const name of names) {
        const pkg = current.packages.find((p) => p.name === name);
        const old = before?.packages.find((p) => p.name === name);
        const display = pkg ?? old!;
        const label = node("div");
        label.append(node("span", name || "(root)", "code"));
        if (compare && (!pkg || !old))
          label.append(node("span", pkg ? "added" : "removed", "badge"));
        const details = node("details");
        details.append(
          node("summary", "Dependencies"),
          node("p", `Imports: ${display.dependsOn.join(", ") || "None"}`),
          node("p", `Imported by: ${display.dependedOnBy.join(", ") || "None"}`),
          node("p", `Source sets: ${display.sourceSets.join(", ")}`),
        );
        label.append(details);
        rows.push({
          name,
          label,
          values: {
            value: pkg?.loc ?? 0,
            ...comparison(pkg?.loc ?? 0, old?.loc ?? 0),
            types: pkg?.types ?? 0,
            out: pkg?.dependsOn.length ?? 0,
            in: pkg?.dependedOnBy.length ?? 0,
            instability: pkg?.instability ?? 0,
          },
        });
      }
      $("table-note").textContent =
        "Complete package inventory for this scope. Instability measures dependency direction: imports out / (imports in + out).";
      const groups = node("details");
      groups.append(
        node("summary", `Dependency cycles: ${current.packageGraph.cycles.length} groups`),
      );
      if (!current.packageGraph.cycles.length) groups.append(node("p", "No cycles in this scope."));
      for (const cycle of current.packageGraph.cycles) {
        const list = node("ul", "", "code");
        list.append(...cycle.map((pkg) => node("li", pkg)));
        groups.append(list);
      }
      $("cycles").append(groups);
    } else if (tab === "hotspots") {
      const kind = select("kind").value;
      const ranked = current.largest[kind] ?? [];
      columns = [
        {
          key: "value",
          label:
            kind === "functionsByCognitiveComplexity"
              ? "Complexity"
              : kind === "packagesByTypes"
                ? "Types"
                : "Lines",
        },
      ];
      for (const item of ranked) {
        const label = node("div");
        const href = sourceLink(selected.commit, item.name);
        const parts = item.name.split(":");
        const name = parts.length > 1 ? parts.at(-1)! : parts[0].split("/").at(-1)!;
        label.append(href ? link(name, href, "code") : node("span", item.name, "code"));
        if (href)
          label.append(
            node("span", parts[0] + (/^\d+$/.test(parts[1] ?? "") ? `:${parts[1]}` : ""), "path"),
          );
        rows.push({ name: item.name, label, values: { value: item.value } });
      }
      $("table-note").textContent =
        `Top ${ranked.length} entries in the selected snapshot. This is a truncated ranking. Changes are not calculated from top lists because an absent item may still exist.`;
    } else if (tab === "sourceSets") {
      columns = [
        { key: "value", label: "Lines" },
        ...(compare
          ? [
              { key: "baseline", label: "Baseline" },
              { key: "change", label: "Δ lines" },
            ]
          : []),
        { key: "files", label: "Files" },
        { key: "cyclomatic", label: "Cyclomatic" },
        { key: "cognitive", label: "Cognitive" },
      ];
      for (const set of current.sourceSets) {
        if (select("kind").value !== "all" && (select("kind").value === "test") !== set.isTest)
          continue;
        const old = before?.sourceSets.find((s) => s.module === set.module && s.name === set.name);
        const label = node("div");
        label.append(
          link(
            set.name,
            `${repository}/tree/${selected.commit}/${set.module}/src/${set.name}`,
            "code",
          ),
          node("span", set.isTest ? "test" : "production", "badge"),
          node("span", set.module, "path"),
        );
        rows.push({
          name: `${set.module} ${set.name}`,
          label,
          values: {
            value: set.loc,
            ...comparison(set.loc, old?.loc ?? 0),
            files: set.files,
            cyclomatic: set.cyclomaticComplexity,
            cognitive: set.cognitiveComplexity,
          },
        });
      }
      $("table-note").textContent =
        "Modules and platform source sets within the selected scope. Test metrics are shown separately from production metrics.";
    } else {
      columns = [
        { key: "value", label: "Value" },
        ...(compare
          ? [
              { key: "baseline", label: "Baseline" },
              { key: "change", label: "Change" },
            ]
          : []),
      ];
      for (const [key, value] of Object.entries(current.summary)) {
        const label = node("div");
        label.append(
          node("span", metricLabel(key)),
          node("span", metricDefinition(key), "metric-explanation"),
        );
        rows.push({
          name: metricLabel(key),
          label,
          values: { value, ...comparison(value, before?.summary[key]) },
        });
      }
      $("table-note").textContent =
        "Production code unless a metric names tests. TODO and suppression counts include tests. Ratios are not percentages.";
    }
    if (!columns.some((column) => column.key === sort) && sort !== "name") sort = "value";
    const query = input("search").value.toLowerCase();
    const filtered = rows
      .filter((row) => row.name.toLowerCase().includes(query))
      .sort((a, b) => {
        const left = sort === "name" ? a.name : (a.values[sort] ?? 0);
        const right = sort === "name" ? b.name : (b.values[sort] ?? 0);
        const order =
          typeof left === "number" && typeof right === "number"
            ? left - right
            : String(left).localeCompare(String(right));
        return descending ? -order : order;
      });
    const head = node("tr");
    for (const column of [
      { key: "name", label: tab === "summary" ? "Metric" : "Name" },
      ...columns,
    ]) {
      const th = node("th", "", column.key === "name" ? "" : "numeric");
      th.scope = "col";
      th.setAttribute(
        "aria-sort",
        column.key === sort ? (descending ? "descending" : "ascending") : "none",
      );
      const button = node(
        "button",
        `${column.label}${column.key === sort ? (descending ? " ↓" : " ↑") : ""}`,
      );
      button.addEventListener("click", () => {
        if (sort === column.key) descending = !descending;
        else {
          sort = column.key;
          descending = column.key !== "name";
        }
        renderTable();
      });
      th.append(button);
      head.append(th);
    }
    $("table-head").replaceChildren(head);
    $("table-body").replaceChildren(
      ...filtered.map((row) => {
        const tr = node("tr");
        const name = node("td");
        name.append(row.label);
        tr.append(name);
        for (const column of columns) {
          const value = row.values[column.key];
          const text =
            value == null
              ? "—"
              : typeof value === "number"
                ? column.key === "change"
                  ? delta(value, 0, 3)
                  : format(value, 3)
                : value;
          tr.append(node("td", text, "numeric"));
        }
        return tr;
      }),
    );
    $("row-count").textContent = `${filtered.length} / ${rows.length} rows`;
    if (!filtered.length) {
      const row = node("tr");
      const cell = node("td", "No matching rows. Clear the filter or select another category.");
      cell.colSpan = columns.length + 1;
      row.append(cell);
      $("table-body").append(row);
    }
  }
  function changeTab(next: string) {
    tab = next;
    sort = tab === "summary" ? "name" : "value";
    descending = tab !== "summary";
    input("search").value = "";
    for (const button of document.querySelectorAll<HTMLButtonElement>("[data-tab]")) {
      const active = button.dataset.tab === tab;
      button.setAttribute("aria-selected", String(active));
      button.tabIndex = active ? 0 : -1;
    }
    $("breakdown").setAttribute("aria-labelledby", `tab-${tab}`);
    $("kind-label").hidden = !["hotspots", "sourceSets"].includes(tab);
    options(
      select("kind"),
      tab === "hotspots"
        ? Object.entries(hotspotKinds)
        : [
            ["all", "Production + tests"],
            ["main", "Production only"],
            ["test", "Tests only"],
          ],
    );
    renderTable();
    save();
  }
  async function load() {
    $("status").hidden = false;
    $("status").textContent = "Loading history…";
    $("retry").hidden = true;
    try {
      history = await read<History>("index.json");
      if (history.schemaVersion !== 2) throw new Error("Regenerate scoped history");
      if (!history.snapshots.length) throw new Error("No measurements");
      select("scope").value = ["library", "demo", "all"].includes(params.get("scope") ?? "")
        ? params.get("scope")!
        : "library";
      select("period").value = ["0", "7", "30", "60"].includes(params.get("days") ?? "")
        ? params.get("days")!
        : "60";
      input("compare").checked = params.get("compare") === "1";
      moduleOptions(params.get("module") ?? "");
      sourceSetOptions(params.get("sourceSet") ?? "");
      changeTab(tab);
      const latest = history.snapshots.at(-1)!;
      $("updated").textContent = `${date(latest.commitDate)} · ${latest.commit.slice(0, 8)}`;
      $("measurement-info").textContent =
        `${history.snapshots.length} snapshots · reporter ${history.reporterVersion}`;
      $("content").hidden = false;
      await loadScope();
    } catch {
      $("status").hidden = false;
      $("status").textContent =
        "History is unavailable or needs updating. Run mise run metrics:site-data for a local dataset, then retry.";
      $("retry").hidden = false;
    }
  }
  select("scope").addEventListener("change", () => {
    moduleOptions("");
    sourceSetOptions("");
    void loadScope();
  });
  select("module").addEventListener("change", () => {
    sourceSetOptions();
    void loadScope();
  });
  select("source-set").addEventListener("change", () => void loadScope());
  select("period").addEventListener("change", filterPeriod);
  select("selected").addEventListener("change", () => void changeSelection());
  select("baseline").addEventListener("change", () => void changeSelection());
  input("compare").addEventListener("change", () => void changeSelection());
  input("releases").addEventListener("change", renderCharts);
  $("reset-zoom").addEventListener("click", () => charts.resetZoom());
  input("search").addEventListener("input", renderTable);
  select("kind").addEventListener("change", renderTable);
  for (const button of document.querySelectorAll<HTMLButtonElement>("[data-view]"))
    button.addEventListener("click", () => {
      view = button.dataset.view!;
      renderCharts();
      save();
    });
  for (const button of document.querySelectorAll<HTMLButtonElement>("[data-tab]")) {
    button.addEventListener("click", () => changeTab(button.dataset.tab!));
    button.addEventListener("keydown", (event) => {
      const tabs = ["packages", "distributions", "hotspots", "sourceSets", "summary"];
      let index = tabs.indexOf(tab);
      if (event.key === "ArrowRight") index = (index + 1) % tabs.length;
      else if (event.key === "ArrowLeft") index = (index + tabs.length - 1) % tabs.length;
      else if (event.key === "Home") index = 0;
      else if (event.key === "End") index = tabs.length - 1;
      else return;
      event.preventDefault();
      changeTab(tabs[index]);
      $(`tab-${tab}`).focus();
    });
  }
  $("retry").addEventListener("click", () => void load());
  $("retry-detail").addEventListener("click", () => void changeSelection());
  void load();
}
