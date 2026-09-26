import { metricReference, appendReferenceDetails } from "./references";
import { distributionMetrics, format, sourceLink, type Snapshot, type Distribution } from "./model";

function node<K extends keyof HTMLElementTagNameMap>(tag: K, text = "", className = "") {
  const element = document.createElement(tag);
  element.textContent = text;
  element.className = className;
  return element;
}
const percent = (count: number, total: number) => (total ? (count / total) * 100 : 0);
const countIn = (d: Distribution | undefined, lower: number, upper: number) =>
  Object.entries(d?.histogram ?? {}).reduce(
    (sum, [value, count]) => (Number(value) >= lower && Number(value) < upper ? sum + count : sum),
    0,
  );

export function renderDistribution(
  host: HTMLElement,
  snapshot: Snapshot,
  baseline: Snapshot | undefined,
  key: string,
  choose: (key: string) => void,
) {
  host.replaceChildren();
  const metric = distributionMetrics[key];
  const current = snapshot.distributions[key];
  const before = baseline?.distributions[key];
  const reference = metricReference(key);
  const control = node("label", "Metric ", "distribution-select");
  const select = node("select");
  select.id = "distribution-metric";
  for (const [id, spec] of Object.entries(distributionMetrics)) {
    const option = node("option", spec.title);
    option.value = id;
    select.append(option);
  }
  select.value = key;
  select.addEventListener("change", () => choose(select.value));
  control.append(select);
  host.append(control, node("p", metric.description, "distribution-definition"));
  if (!current || !current.histogram) {
    host.append(
      node(
        "p",
        "This snapshot has no frequency data. Regenerate history with the current reporter.",
      ),
    );
    return;
  }
  if (!current.count) {
    host.append(node("p", `No ${metric.population} in this scope.`));
    return;
  }
  const stats = node("dl", "", "distribution-stats");
  const statistics: [string, keyof Distribution][] = [
    ["Count", "count"],
    ["Mean", "mean"],
    ["Median (p50)", "p50"],
    ["p75", "p75"],
    ["p90", "p90"],
    ["p99", "p99"],
    ["Max", "max"],
  ];
  for (const [label, field] of statistics) {
    const item = node("div");
    item.dataset.stat = field;
    item.append(node("dt", label), node("dd", format(current[field] as number)));
    if (before) item.append(node("dd", `was ${format(before[field] as number)}`, "baseline-stat"));
    stats.append(item);
  }
  host.append(stats);
  const referenceSection = node("section", "", "distribution-reference");
  if (reference.threshold != null) {
    const above = countIn(current, reference.threshold + 1, Infinity);
    const assessment = node("p", `Above review reference (>${reference.threshold}): `);
    assessment.className = "threshold-count";
    assessment.append(
      node(
        "strong",
        `${format(above)} / ${format(current.count)} ${metric.population} (${format(percent(above, current.count))}%)`,
      ),
    );
    if (before) {
      const oldAbove = countIn(before, reference.threshold + 1, Infinity);
      assessment.append(
        document.createTextNode(
          ` · Baseline: ${format(oldAbove)} / ${format(before.count)} (${format(percent(oldAbove, before.count))}%)`,
        ),
      );
    }
    referenceSection.append(assessment);
  }
  const referenceDetails = node("details");
  referenceDetails.append(node("summary", reference.label));
  appendReferenceDetails(referenceDetails, reference);
  referenceSection.append(referenceDetails);
  host.append(referenceSection);
  host.append(
    node(
      "p",
      `Distribution of ${format(current.count)} production ${metric.population}. Bars show the percentage in each labeled range; ranges have different widths. ${before ? "Blue: selected snapshot. Grey: baseline; each uses its own population total." : "Counts include every observation, not just the top-ranked entries."}`,
      "distribution-caption",
    ),
  );
  const table = node("table", "", "histogram");
  table.setAttribute("aria-label", `${metric.title} distribution`);
  const head = node("thead");
  const header = node("tr");
  for (const label of [
    metric.unit,
    "Share",
    "Count",
    "%",
    ...(before ? ["Baseline count", "Baseline %"] : []),
  ]) {
    const th = node("th", label);
    th.scope = "col";
    header.append(th);
  }
  head.append(header);
  const body = node("tbody");
  const boundaries = [
    ...new Set([
      ...metric.boundaries,
      ...(reference.threshold == null ? [] : [reference.threshold + 1]),
    ]),
  ].sort((a, b) => a - b);
  boundaries.forEach((lower, i) => {
    const upper = boundaries[i + 1] ?? Infinity;
    const count = countIn(current, lower, upper);
    const old = countIn(before, lower, upper);
    const label =
      upper === Infinity
        ? `${lower}+`
        : upper === lower + 1
          ? String(lower)
          : `${lower}–${upper - 1}`;
    const row = node("tr");
    if (reference.threshold != null && lower > reference.threshold)
      row.className = "above-reference";
    const name = node("th", label);
    name.scope = "row";
    const bars = node("td", "", "histogram-bars");
    for (const [amount, total, className] of [
      [count, current.count, "selected-bar"],
      ...(before ? [[old, before.count, "baseline-bar"]] : []),
    ] as [number, number, string][]) {
      const track = node("div", "", "histogram-track");
      track.setAttribute("aria-hidden", "true");
      const bar = node("span", "", className);
      bar.style.width = `${percent(amount, total)}%`;
      track.append(bar);
      bars.append(track);
    }
    row.append(
      name,
      bars,
      node("td", format(count), "numeric bin-count"),
      node("td", `${format(percent(count, current.count))}%`, "numeric"),
    );
    if (before)
      row.append(
        node("td", format(old), "numeric baseline-count"),
        node("td", `${format(percent(old, before.count))}%`, "numeric"),
      );
    body.append(row);
  });
  table.append(head, body);
  const scroll = node("div", "", "histogram-scroll");
  scroll.append(table);
  host.append(scroll);
  host.append(
    node(
      "p",
      "Percentiles use nearest rank: p90 is a value at or below which at least 90% of observations fall. Median uses p50 by the same rule (not the average of the middle two values). Max is the single highest observed value.",
      "distribution-caption",
    ),
  );
  if (current.maxName) {
    const outlier = node(
      "p",
      `Maximum: ${format(current.max)} ${metric.unit} · `,
      "distribution-outlier",
    );
    const href = sourceLink(snapshot.commit, current.maxName);
    const source = node("a", current.maxName, "code");
    if (href) source.href = href;
    outlier.append(source);
    host.append(outlier);
  }
}
