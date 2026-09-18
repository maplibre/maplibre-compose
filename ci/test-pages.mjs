import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const [playwrightModule, url, screenshot] = process.argv.slice(2);
const { chromium } = await import(pathToFileURL(playwrightModule).href);
const browser = await chromium.launch({ channel: "chromium" });
try {
  const page = await browser.newPage();
  page.setDefaultTimeout(30_000);
  const resources = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.includes("/composeResources/")) {
      resources.push(request);
    }
  });
  await page.goto(url);
  await page.locator('canvas[role="generic"]').waitFor();
  await page.screenshot({ path: screenshot });
  assert.ok(resources.length > 0, "The demo requested no Compose resources.");

  // Keep the page alive until each observed response body completes. Closing after the
  // screenshot can abort a font or image request and write status -1 into a HAR.
  let checked = 0;
  const deadline = Date.now() + 30_000;
  while (checked < resources.length) {
    const request = resources[checked++];
    assert.ok(
      request.url().startsWith(new URL("composeResources/", url).href),
      `Compose resource outside the demo: ${request.url()}`,
    );
    let timer;
    try {
      await Promise.race([
        (async () => {
          const response = await request.response();
          assert.ok(response, `Compose resource failed: ${request.url()}`);
          assert.ok(
            response.status() >= 200 && response.status() < 400,
            `${response.status()} ${request.url()}`,
          );
          assert.equal(await response.finished(), null, `Incomplete resource: ${request.url()}`);
        })(),
        new Promise((_, reject) => {
          timer = setTimeout(
            () => reject(new Error(`Timed out loading ${request.url()}`)),
            Math.max(0, deadline - Date.now()),
          );
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  }
} finally {
  await browser.close();
}
