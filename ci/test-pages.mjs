import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const [playwrightModule, url] = process.argv.slice(2);
const { chromium } = await import(pathToFileURL(playwrightModule).href);
const browser = await chromium.launch({ channel: "chromium" });
try {
  const page = await browser.newPage();
  page.setDefaultTimeout(30_000);
  const isComposeResource = (request) =>
    new URL(request.url()).pathname.includes("/composeResources/");
  const resources = [];
  page.on("request", (request) => {
    if (isComposeResource(request)) {
      resources.push(request);
    }
  });
  await page.goto(url);
  await page.locator('canvas[role="generic"]').waitFor();
  // The canvas exists before the first composition requests any resource, and the network is
  // already idle while Compose initializes, so wait for the first resource request before
  // waiting for quiescence, including complete response bodies.
  if (resources.length === 0) {
    await page.waitForRequest(isComposeResource);
  }
  await page.waitForLoadState("networkidle");
  assert.ok(resources.length > 0, "The demo requested no Compose resources.");
  for (const request of resources) {
    assert.ok(
      request.url().startsWith(new URL("composeResources/", url).href),
      `Compose resource outside the demo: ${request.url()}`,
    );
    const response = await request.response();
    assert.ok(response, `Compose resource failed: ${request.url()}`);
    assert.ok(
      response.status() >= 200 && response.status() < 400,
      `${response.status()} ${request.url()}`,
    );
    assert.equal(await response.finished(), null, `Incomplete resource: ${request.url()}`);
  }
} finally {
  await browser.close();
}
