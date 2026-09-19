// Invoked by run.py with Playwright, output directory, configuration, and server URL.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.argv[2]);
const output = path.resolve(process.argv[3]);
const config = process.argv[4] || '{}';
(async () => {
  fs.mkdirSync(output, { recursive: true });
  if (fs.existsSync(path.join(output, 'screen.webm'))) throw new Error('Output exists');
  const browser = await chromium.launch({ headless: false, args: ['--disable-background-timer-throttling', '--disable-renderer-backgrounding'] });
  const logs = [];
  let timeout;
  try {
    // Record the composed page. captureStream on the shared GL canvas can miss
    // Compose-only changes such as the measurement gate on an otherwise idle map.
    const context = await browser.newContext({ viewport: { width: 640, height: 480 }, deviceScaleFactor: 1, recordVideo: { dir: output, size: { width: 640, height: 480 } } });
    const page = await context.newPage();
    let finish;
    const completed = new Promise(resolve => { finish = resolve; });
    timeout = setTimeout(() => finish(false), 120000);
    page.on('console', m => {
      logs.push(m.text());
      if (m.text().includes('MAP_BENCHMARK DONE')) finish(true);
      if (m.text().includes('MAP_BENCHMARK ERROR')) finish(false);
    });
    page.on('pageerror', e => { logs.push(`MAP_BENCHMARK ERROR ${e}`); finish(false); });
    const url = new URL(process.argv[5] || 'http://127.0.0.1:8765/');
    url.searchParams.set('benchmark', config);
    await page.goto(url.href);
    await page.waitForSelector('canvas:not(.maplibregl-canvas)');
    const metadata = await page.locator('canvas[role=generic]').evaluate(canvas => {
      const gl = canvas.getContext('webgl2');
      const extension = gl?.getExtension('WEBGL_debug_renderer_info');
      return { width: canvas.width, height: canvas.height, userAgent: navigator.userAgent, renderer: extension && gl.getParameter(extension.UNMASKED_RENDERER_WEBGL), capture: 'playwright-page-video' };
    });
    const success = await completed;
    clearTimeout(timeout);
    await new Promise(resolve => setTimeout(resolve, 500));
    await context.close();
    fs.renameSync(await page.video().path(), path.join(output, 'screen.webm'));
    fs.writeFileSync(path.join(output, 'browser.json'), JSON.stringify(metadata, null, 2));
    if (!success) throw new Error('Benchmark failed or timed out; inspect app.log');
  } finally {
    clearTimeout(timeout);
    fs.writeFileSync(path.join(output, 'app.log'), logs.join('\n'));
    await browser.close();
  }
})().catch(e => { console.error(e); process.exit(1); });
