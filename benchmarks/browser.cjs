// Usage: node browser.cjs <playwright module> <output directory> [surface,ease,60]
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.argv[2]);
const output = path.resolve(process.argv[3]);
const config = process.argv[4] || 'animation,surface,default,0';
(async () => {
  fs.mkdirSync(output, { recursive: true });
  if (fs.existsSync(path.join(output, 'screen.webm'))) throw new Error('Output exists');
  const browser = await chromium.launch({ headless: false, args: ['--disable-background-timer-throttling', '--disable-renderer-backgrounding'] });
  try {
    const page = await browser.newPage({ viewport: { width: 640, height: 480 }, deviceScaleFactor: 1 });
    const logs = [];
    page.on('console', m => logs.push(m.text()));
    page.on('pageerror', e => logs.push(String(e)));
    await page.goto((process.argv[5] || 'http://127.0.0.1:8765/') + '?benchmark=' + config);
    await page.waitForSelector('canvas:not(.maplibregl-canvas)');
    const metadata = await page.locator('canvas[role=generic]').evaluate(canvas => {
      const gl = canvas.getContext('webgl2');
      const extension = gl?.getExtension('WEBGL_debug_renderer_info');
      return { width: canvas.width, height: canvas.height, userAgent: navigator.userAgent, renderer: extension && gl.getParameter(extension.UNMASKED_RENDERER_WEBGL) };
    });
    const encoded = await page.locator('canvas[role=generic]').evaluate(canvas => new Promise(resolve => {
      const stream = canvas.captureStream(60);
      const chunks = [];
      const recorder = new MediaRecorder(stream, { mimeType: 'video/webm;codecs=vp9', videoBitsPerSecond: 20000000 });
      recorder.ondataavailable = e => chunks.push(e.data);
      recorder.onstop = () => {
        stream.getTracks().forEach(track => track.stop());
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result.split(',')[1]);
        reader.readAsDataURL(new Blob(chunks, { type: 'video/webm' }));
      };
      recorder.start();
      setTimeout(() => recorder.stop(), 22000);
    }));
    fs.writeFileSync(path.join(output, 'screen.webm'), Buffer.from(encoded, 'base64'));
    fs.writeFileSync(path.join(output, 'app.log'), logs.join('\n'));
    fs.writeFileSync(path.join(output, 'browser.json'), JSON.stringify(metadata, null, 2));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exit(1); });
