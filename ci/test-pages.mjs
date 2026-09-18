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
  let inFlight = 0;
  let lastActivity = Date.now();
  const settle = () => {
    inFlight -= 1;
    lastActivity = Date.now();
  };
  page.on("request", (request) => {
    inFlight += 1;
    lastActivity = Date.now();
    if (isComposeResource(request)) {
      resources.push(request);
    }
  });
  page.on("requestfinished", settle);
  page.on("requestfailed", settle);
  await page.goto(url);
  await page.locator('canvas[role="generic"]').waitFor();
  // The canvas exists before the first composition requests any resource, and the page already
  // reached network idle while Compose initialized, so Playwright's load state cannot be reused.
  // Wait for the first resource request, then for a fresh quiet period with complete bodies.
  if (resources.length === 0) {
    await page.waitForRequest(isComposeResource).catch(() => {
      assert.fail("The demo requested no Compose resources.");
    });
  }
  const deadline = Date.now() + 30_000;
  while (inFlight > 0 || Date.now() - lastActivity < 500) {
    assert.ok(Date.now() < deadline, `The network did not settle; ${inFlight} requests in flight.`);
    await page.waitForTimeout(100);
  }
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
