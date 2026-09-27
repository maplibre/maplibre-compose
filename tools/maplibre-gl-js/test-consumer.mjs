import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";

const [playwrightModule, distribution] = process.argv.slice(2);
const playwright = await import(pathToFileURL(playwrightModule).href);
const { chromium } = playwright.default ?? playwright;
const root = pathToFileURL(`${distribution}/`);
const server = createServer(async (request, response) => {
  if (request.url === "/") {
    response.setHeader("Content-Type", "text/html; charset=utf-8");
    response.end('<!doctype html><script defer src="maplibre-js-consumer.js"></script>');
    return;
  }
  try {
    response.setHeader("Content-Type", request.url.endsWith(".wasm") ? "application/wasm" : "text/javascript; charset=utf-8");
    response.end(await readFile(new URL(`.${request.url}`, root)));
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const url = `http://127.0.0.1:${server.address().port}/`;
let browser;
try {
  browser = await chromium.launch({ args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"] });
  const page = await browser.newPage();
  const errors = [];
  const external = [];
  const workers = [];
  page.on("pageerror", (error) => errors.push(String(error)));
  page.on("worker", (worker) => workers.push(worker.url()));
  await page.route("**/*", (route) => {
    if (route.request().url().startsWith(url)) return route.continue();
    external.push(route.request().url());
    return route.abort();
  });
  await page.goto(url);
  await page.waitForFunction(() => document.body.hasAttribute("data-result"), null, { timeout: 30_000 });
  assert.equal(await page.locator("body").getAttribute("data-result"), "passed");
  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  assert.ok(workers.length > 0 && workers.every((worker) => worker.startsWith("blob:")));
  console.log("Published Compose consumer rendered and queried GeoJSON with its embedded worker; no external requests.");
} finally {
  await browser?.close();
  server.close();
}
