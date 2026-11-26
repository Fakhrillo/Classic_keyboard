// Renders the keyboard at rest plus one "pressed" patch per key, with blank faces, for the Android view.
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [,, html, outDir] = process.argv;
  const W = 1440, H = Math.round(W / 1.62), VIEW_W = 10.78, VIEW_CY = -1.72, D = 0.915, U = W / VIEW_W;
  fs.mkdirSync(outDir, { recursive: true });
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('pageerror:', e.message));
  await p.route('**/three.min.js', r => r.fulfill({ path: require.resolve('three/build/three.min.js'), contentType: 'text/javascript' }));
  await p.route('**fonts.googleapis.com/**', r => r.abort());
  await p.goto('file://' + html + '?bare=' + W + '&blank');
  await p.waitForFunction('window.__ready', null, { timeout: 20000 });
  const settle = () => p.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => requestAnimationFrame(r)))));
  await p.evaluate(() => window.__pose(-1)); await settle();
  await p.screenshot({ path: outDir + '/rest.png', clip: { x: 0, y: 0, width: W, height: H }, timeout: 90000 });
  const geom = await p.evaluate(() => window.__geom());
  const X = u => W / 2 + (u - 5) * U, Y = u => H / 2 - (u - VIEW_CY) * U;
  const lines = [[W, H, U.toFixed(4), VIEW_CY, geom.length].join(' ')];
  for (let i = 0; i < geom.length; i++) {
    const k = geom[i];
    const x0 = Math.max(0, Math.floor(X(k.cx - k.w / 2 - 0.2))), x1 = Math.min(W, Math.ceil(X(k.cx + k.w / 2 + 0.65)));
    const y0 = Math.max(0, Math.floor(Y(k.cy + D / 2 + 0.2))), y1 = Math.min(H, Math.ceil(Y(k.cy - D / 2 - 0.85)));
    await p.evaluate(j => window.__pose(j), i); await settle();
    await p.screenshot({ path: outDir + '/k' + i + '.png', clip: { x: x0, y: y0, width: x1 - x0, height: y1 - y0 }, timeout: 90000 });
    lines.push([i, k.row, k.cx, k.cy, k.w, k.reach, x0, y0, x1 - x0, y1 - y0].join(' '));
  }
  fs.writeFileSync(outDir + '/keys.txt', lines.join('\n') + '\n');
  await b.close();
})();
