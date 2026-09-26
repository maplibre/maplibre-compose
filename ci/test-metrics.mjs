import assert from "node:assert/strict";
import { readFile, mkdir } from "node:fs/promises";
import { createServer } from "node:http";
import { resolve, extname } from "node:path";
import { pathToFileURL } from "node:url";

// Serve the built site at the same prefix used by Pages, without deploying it.
const root = resolve("docs/dist");
const prefix = "/maplibre-compose/";
const types = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".woff2": "font/woff2",
  ".svg": "image/svg+xml",
};
const server = createServer(async (req, res) => {
  const pathname = new URL(req.url, "http://localhost").pathname;
  const path = resolve(
    root,
    `.${pathname.slice(prefix.length - 1)}${pathname.endsWith("/") ? "index.html" : ""}`,
  );
  if (!pathname.startsWith(prefix) || !path.startsWith(`${root}/`)) {
    res.writeHead(404).end();
    return;
  }
  try {
    const content = await readFile(path);
    res
      .writeHead(200, { "Content-Type": types[extname(path)] ?? "application/octet-stream" })
      .end(content);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
const url = `http://127.0.0.1:${server.address().port}${prefix}`;
const { chromium } = await import(pathToFileURL(process.argv[2]).href);
const browser = await chromium.launch({ channel: "chromium" });
try {
  const history = JSON.parse(await readFile(`${root}/metrics-data/index.json`, "utf8"));
  assert.equal(history.schemaVersion, 2);
  assert.ok(history.snapshots.length >= 3, "Generate at least three snapshots");
  const first = history.snapshots[0];
  const last = history.snapshots.at(-1);
  const library = history.scopes.find(
    (scope) => scope.group === "library" && !scope.module && !scope.sourceSet,
  );
  const demo = history.scopes.find(
    (scope) => scope.group === "demo" && !scope.module && !scope.sourceSet,
  );
  const reportAt = async (entry, scope) =>
    JSON.parse(await readFile(`${root}/metrics-data/${entry.path}${scope.id}.json`, "utf8"));
  const report = await reportAt(last, library);
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
  const errors = [];
  const requests = [];
  browser.on("page", (tab) => tab.on("pageerror", (error) => errors.push(error.message)));
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (request.url().includes("/metrics-data/")) requests.push(request.url());
  });
  const ready = (tab = page) => tab.locator("#detail-content").waitFor({ state: "visible" });
  await page.goto(`${url}metrics/`);
  await ready();
  assert.equal(await page.locator("#scope").inputValue(), "library");
  assert.equal(await page.locator("#selected").inputValue(), last.commit);
  assert.equal(await page.locator("#compare").isChecked(), false);
  assert.equal(await page.locator(".panel").count(), 6);
  assert.equal(await page.locator("#table-body tr").count(), report.packages.length);
  assert.equal(requests.length, 3, "Load only index, selected scope series, and selected snapshot");
  assert.match(
    await page.locator(".chart").first().getAttribute("aria-label"),
    new RegExp(`p90 ${report.summary.functionCognitiveP90}`),
  );

  await page.locator("#compare").check();
  await ready();
  await page.locator("#baseline").selectOption(last.commit);
  await ready();
  assert.ok(
    (await page.locator("#table-body tr td:nth-child(4)").allTextContents()).every(
      (value) => value === "0",
    ),
  );
  await page.locator("#compare").uncheck();
  await ready();
  // Header sorting must change numeric order, not just its indicator.
  await page.getByRole("button", { name: "Lines ↓", exact: true }).click();
  const sizes = (await page.locator("#table-body tr td:nth-child(2)").allTextContents()).map(
    (text) => Number(text.replaceAll(",", "")),
  );
  assert.deepEqual(
    sizes,
    [...sizes].sort((a, b) => a - b),
  );
  await page.locator("#search").fill("not-a-package");
  assert.match(await page.locator("#table-body").textContent(), /No matching rows/);
  await page.locator("#search").fill("interaction");
  assert.equal(
    await page.locator("#table-body tr").count(),
    report.packages.filter((pkg) => pkg.name.includes("interaction")).length,
  );
  await page.locator("#search").fill("");

  await page.locator("#scope").selectOption("demo");
  await ready();
  const demoReport = await reportAt(last, demo);
  assert.equal(await page.locator("#table-body tr").count(), demoReport.packages.length);
  await page.locator("#scope").selectOption("library");
  await ready();
  await page.locator("#module").selectOption("lib/maplibre-compose");
  await ready();
  await page.locator("#source-set").selectOption("commonMain");
  await ready();
  const common = history.scopes.find(
    (scope) => scope.module === "lib/maplibre-compose" && scope.sourceSet === "commonMain",
  );
  const commonReport = await reportAt(last, common);
  assert.match(
    await page.locator(".chart").first().getAttribute("aria-label"),
    new RegExp(`p90 ${commonReport.summary.functionCognitiveP90}`),
  );
  await page.locator("#tab-sourceSets").click();
  assert.equal(await page.locator("#table-body tr").count(), 1);
  assert.match(await page.locator("#table-body").textContent(), /commonMain/);
  await page.reload();
  await ready();
  assert.equal(await page.locator("#module").inputValue(), "lib/maplibre-compose");
  assert.equal(await page.locator("#source-set").inputValue(), "commonMain");

  await page.locator("#module").selectOption("");
  await ready();
  await page.locator("#source-set").selectOption("");
  await ready();
  await page.locator("#tab-sourceSets").click();
  await page.locator("#kind").selectOption("test");
  assert.equal(
    await page.locator("#table-body tr").count(),
    report.sourceSets.filter((set) => set.isTest).length,
  );
  await page.locator("#tab-hotspots").click();
  assert.match(
    await page.locator("#table-body a").first().getAttribute("href"),
    new RegExp(`/blob/${last.commit}/.*#L\\d+$`),
  );
  await page.locator("#tab-hotspots").focus();
  await page.keyboard.press("ArrowRight");
  assert.equal(await page.locator("#tab-sourceSets").getAttribute("aria-selected"), "true");
  await page.locator('[data-view="complexity"]').click();
  assert.equal(await page.locator(".panel").count(), 4);
  await page.locator('[data-view="dependencies"]').click();
  assert.equal(await page.locator(".panel").count(), 6);
  await page.locator('[data-view="overview"]').click();
  await page.locator("#period").selectOption("7");
  await ready();
  const latestDate = Math.max(...history.snapshots.map((entry) => Date.parse(entry.commitDate)));
  assert.equal(
    await page.locator("#selected option").count(),
    history.snapshots.filter((entry) => Date.parse(entry.commitDate) >= latestDate - 7 * 86400000)
      .length,
  );
  await page.locator("#period").selectOption("0");
  await ready();
  await page
    .locator(".chart canvas")
    .first()
    .click({ position: { x: 160, y: 95 } });
  await ready();
  assert.notEqual(
    await page.locator("#selected").inputValue(),
    last.commit,
    "Chart click selects a historical snapshot",
  );
  await page.locator("#selected").selectOption(last.commit);
  await ready();
  await page.locator("#tab-packages").click();
  await mkdir("build/metrics/screenshots", { recursive: true });
  await page.screenshot({ path: "build/metrics/screenshots/desktop.png" });
  // Inspecting history must leave every chart readable and the selected snapshot unchanged.
  const values = page.locator(".series-value b");
  const selectedValues = await values.allTextContents();
  const beforeHoverRequests = requests.length;
  await page
    .locator(".chart")
    .first()
    .hover({ position: { x: 200, y: 70 } });
  const hoveredCommit = await page.locator(".inspection").getAttribute("data-commit");
  assert.notEqual(hoveredCommit, last.commit);
  const hovered = await reportAt(
    history.snapshots.find((entry) => entry.commit === hoveredCommit),
    library,
  );
  const keys = [
    "functionCognitiveP50",
    "functionCognitiveP90",
    "functionCognitiveP99",
    "functionLinesP50",
    "functionLinesP90",
    "functionLinesP99",
    "cyclomaticPer1000Lloc",
    "packagesInCycles",
    "loc",
    "testLoc",
    "fileLocP50",
    "fileLocP90",
    "fileLocP99",
  ];
  assert.deepEqual(
    await values.allTextContents(),
    keys.map((key) =>
      new Intl.NumberFormat("en", { maximumFractionDigits: 1 }).format(hovered.summary[key]),
    ),
  );
  assert.equal(await page.locator("#selected").inputValue(), last.commit);
  assert.equal(
    requests.length,
    beforeHoverRequests,
    "Hover uses loaded series without fetching snapshots",
  );
  await page.screenshot({ path: "build/metrics/screenshots/desktop-hover.png" });
  await page.locator("h1").hover();
  assert.deepEqual(
    await values.allTextContents(),
    selectedValues,
    "Leaving charts restores selected snapshot values",
  );
  assert.equal(await page.locator(".inspection-mode").textContent(), "Selected");
  const firstTail = page.getByRole("button", {
    name: "Show p99 in Function cognitive complexity",
    exact: true,
  });
  await firstTail.click();
  assert.equal(await firstTail.getAttribute("aria-pressed"), "false");
  assert.equal(
    await page
      .getByRole("button", { name: "Show p99 in Function length", exact: true })
      .getAttribute("aria-pressed"),
    "true",
  );
  await page
    .locator(".chart")
    .first()
    .click({ position: { x: 200, y: 70 } });
  await ready();
  assert.equal(
    await firstTail.getAttribute("aria-pressed"),
    "false",
    "Series visibility survives snapshot selection",
  );
  await page.locator("#selected").selectOption(last.commit);
  await ready();
  await firstTail.click();

  await page
    .getByRole("button", { name: "Function cognitive complexity distribution", exact: true })
    .click();
  assert.equal(await page.locator("#tab-distributions").getAttribute("aria-selected"), "true");
  const distributionKeys = Object.keys(report.distributions);
  for (const key of distributionKeys) {
    await page.locator("#distribution-metric").selectOption(key);
    const distribution = report.distributions[key];
    const counts = (await page.locator(".bin-count").allTextContents()).map((value) =>
      Number(value.replaceAll(",", "")),
    );
    assert.equal(
      counts.reduce((sum, n) => sum + n, 0),
      distribution.count,
      `All observations must occur once in ${key}`,
    );
    assert.equal(
      await page.locator('[data-stat="p50"] dd').textContent(),
      new Intl.NumberFormat("en", { maximumFractionDigits: 1 }).format(distribution.p50),
    );
    assert.ok((await page.locator(".distribution-definition").textContent()).length > 80);
    assert.match(
      await page.locator(".distribution-outlier a").getAttribute("href"),
      new RegExp(`/blob/${last.commit}/`),
    );
  }
  await page.locator("#distribution-metric").selectOption("functionCognitiveComplexity");
  const cognitive = report.distributions.functionCognitiveComplexity;
  const overReference = Object.entries(cognitive.histogram).reduce(
    (sum, [value, count]) => (Number(value) > 15 ? sum + count : sum),
    0,
  );
  const highlighted = (await page.locator(".above-reference .bin-count").allTextContents()).map(
    (value) => Number(value.replaceAll(",", "")),
  );
  assert.equal(
    highlighted.reduce((sum, count) => sum + count, 0),
    overReference,
    "The 15 reference must split bins exactly; values equal to 15 are not above it",
  );
  assert.match(
    await page.locator(".threshold-count strong").textContent(),
    new RegExp(`^${overReference.toLocaleString("en")} /`),
  );
  await page.locator(".distribution-reference summary").click();
  assert.match(
    await page.locator(".distribution-reference").textContent(),
    /configurable review heuristic/,
  );
  assert.ok(
    await page
      .locator('.reference-sources a[href="https://arxiv.org/abs/2007.12520"]')
      .last()
      .isVisible(),
  );
  await page.locator(".distribution-reference summary").click();
  await page.locator("#compare").check();
  await ready();
  await page.locator("#baseline").selectOption(last.commit);
  await ready();
  assert.deepEqual(
    await page.locator(".bin-count").allTextContents(),
    await page.locator(".baseline-count").allTextContents(),
  );
  await page.screenshot({ path: "build/metrics/screenshots/distribution.png", fullPage: true });
  await page.locator("#compare").uncheck();
  await ready();
  await page.locator("#tab-summary").click();
  assert.ok(!(await page.locator("#table-body").textContent()).includes("No definition available"));
  await page.locator("#tab-packages").click();
  await page.setViewportSize({ width: 390, height: 844 });
  for (const tab of ["packages", "distributions", "hotspots", "sourceSets", "summary"]) {
    await page.locator(`#tab-${tab}`).click();
    assert.ok(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      `Mobile overflow in ${tab}`,
    );
  }
  await page.screenshot({ path: "build/metrics/screenshots/mobile-details.png" });
  await page.evaluate(() => scrollTo(0, 0));
  await page.screenshot({ path: "build/metrics/screenshots/mobile.png" });

  const middle = history.snapshots[Math.floor(history.snapshots.length / 2)];
  const middlePath = `${middle.path}${library.id}.json`;
  const fresh = await browser.newPage();
  await fresh.route(`**/${middlePath}`, (route) =>
    route.fulfill({ status: 503, body: "Unavailable" }),
  );
  await fresh.goto(`${url}metrics/?commit=${middle.commit}`);
  await fresh.locator("#retry-detail").waitFor({ state: "visible" });
  assert.equal(await fresh.locator("#detail-content").isVisible(), false);
  await fresh.unroute(`**/${middlePath}`);
  await fresh.locator("#retry-detail").click();
  await ready(fresh);
  await fresh.close();

  // Older pending responses must not replace the newly selected commit's rows.
  const race = await browser.newPage();
  await race.goto(`${url}metrics/`);
  await ready(race);
  let release, intercepted;
  const gate = new Promise((done) => {
    release = done;
  });
  const pending = new Promise((done) => {
    intercepted = done;
  });
  await race.route(`**/${middlePath}`, async (route) => {
    intercepted();
    await gate;
    await route.continue();
  });
  await race.locator("#selected").selectOption(middle.commit);
  await pending;
  await race.locator("#selected").selectOption(last.commit);
  await ready(race);
  const finished = race.waitForResponse((response) => response.url().endsWith(middlePath));
  release();
  await (await finished).finished();
  await race.locator("#tab-hotspots").click();
  assert.match(
    await race.locator("#table-body a").first().getAttribute("href"),
    new RegExp(`/blob/${last.commit}/`),
  );
  await race.close();

  const unavailable = await browser.newPage();
  await unavailable.route("**/metrics-data/index.json", (route) =>
    route.fulfill({ status: 404, body: "Missing" }),
  );
  await unavailable.goto(`${url}metrics/`);
  await unavailable.locator("#retry").waitFor({ state: "visible" });
  assert.match(await unavailable.locator("#status").textContent(), /metrics:site-data/);
  await unavailable.unroute("**/metrics-data/index.json");
  await unavailable.locator("#retry").click();
  await ready(unavailable);
  await unavailable.close();

  const single = await browser.newPage();
  await single.route("**/metrics-data/index.json", (route) =>
    route.fulfill({ json: { ...history, snapshots: [last] } }),
  );
  await single.goto(`${url}metrics/`);
  await ready(single);
  assert.equal(await single.locator("#selected option").count(), 1);
  assert.equal(await single.locator(".chart canvas").count(), 6);
  await single.close();
  // A historical scope can have no observations inside a recent date range.
  const absent = await browser.newPage();
  const points = JSON.parse(await readFile(`${root}/metrics-data/${library.path}`, "utf8"));
  await absent.route(`**/${library.path}`, (route) => route.fulfill({ json: [points[0]] }));
  await absent.goto(`${url}metrics/`);
  await ready(absent);
  assert.equal(await absent.locator("#selected").inputValue(), first.commit);
  await absent.locator("#period").selectOption("7");
  assert.equal(await absent.locator("#selected").isDisabled(), true);
  await absent.locator('[data-view="size"]').click();
  assert.equal(await absent.locator(".panel").count(), 0);
  assert.match(await absent.locator("#status").textContent(), /No measurements/);
  await absent.locator("#period").selectOption("0");
  await ready(absent);
  assert.equal(await absent.locator("#selected").isDisabled(), false);
  await absent.close();
  assert.deepEqual(errors, []);
  console.log(
    `Verified scoped dashboard with ${history.snapshots.length} snapshots: scope filters, lazy loading, comparison, sorting, charts, source links, keyboard controls, URL state, mobile layout, and error recovery.`,
  );
} finally {
  await browser.close();
  await new Promise((done) => server.close(done));
}
