// Run the shared browser workload and retain its measurement log.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.argv[2]);
(async () => {
  const browser = await chromium.launch({ headless: false, args: ['--disable-background-timer-throttling', '--disable-renderer-backgrounding'] });
  const logs = [];
  const events = [];
  let tracing;
  let timeout;
  try {
    const page = await browser.newPage({ viewport: { width: 640, height: 480 }, deviceScaleFactor: 1 });
    if (JSON.parse(process.argv[4]).workload === 'animation') {
      tracing = await page.context().newCDPSession(page);
      tracing.on('Tracing.dataCollected', data => events.push(...data.value));
      await tracing.send('Tracing.start', {
        categories: 'cc,benchmark,blink.user_timing',
        options: 'record-as-much-as-possible',
      });
    }
    let finish;
    const completed = new Promise(resolve => { finish = resolve; });
    timeout = setTimeout(() => finish(false), 120000);
    page.on('console', message => {
      logs.push(message.text());
      if (message.text().includes('MAP_BENCHMARK DONE')) finish(true);
      if (message.text().includes('MAP_BENCHMARK ERROR')) finish(false);
    });
    page.on('pageerror', error => { logs.push(`MAP_BENCHMARK ERROR ${error}`); finish(false); });
    const url = new URL(process.argv[5]);
    url.searchParams.set('benchmark', process.argv[4]);
    await page.goto(url.href);
    if (!await completed) throw new Error('Benchmark failed or timed out; inspect app.log');
  } finally {
    clearTimeout(timeout);
    try {
      if (tracing) {
        const ended = new Promise(resolve => tracing.once('Tracing.tracingComplete', resolve));
        await tracing.send('Tracing.end');
        const result = await ended;
        fs.writeFileSync(path.join(process.argv[3], 'browser-trace.json'), JSON.stringify({ traceEvents: events, dataLossOccurred: result.dataLossOccurred }));
      }
    } finally {
      fs.writeFileSync(path.join(process.argv[3], 'app.log'), logs.join('\n'));
      await browser.close();
    }
  }
})().catch(error => { console.error(error); process.exit(1); });
