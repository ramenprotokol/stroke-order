// A small Chrome DevTools Protocol driver (no dependencies): launches headless Chrome,
// opens pages with exact device emulation (headless Chrome won't size a window below
// 500 px), records console errors and exceptions, and dispatches real pointer input.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export function findChrome(env = process.env) {
  if (env.CHROME_PATH) return existsSync(env.CHROME_PATH) ? env.CHROME_PATH : null;
  return [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ].find((p) => existsSync(p)) ?? null;
}

/** Locally a missing Chrome skips the browser tests; REQUIRE_BROWSER=1 (CI) makes it fail. */
export function browserPlan(chrome, env = process.env) {
  if (chrome) return { run: true };
  if (env.REQUIRE_BROWSER === '1') return { fail: 'REQUIRE_BROWSER=1 but Chrome was not found (set CHROME_PATH).' };
  return { skip: 'Chrome not found; set CHROME_PATH to run the browser tests.' };
}

export async function launchChrome(chromePath) {
  const profile = mkdtempSync(join(tmpdir(), 'stroke-order-chrome-'));
  const proc = spawn(chromePath, [
    '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--hide-scrollbars', '--force-color-profile=srgb', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  const wsUrl = await new Promise((resolve, reject) => {
    let buf = '';
    const timer = setTimeout(() => reject(new Error('Chrome did not start within 20 s')), 20000);
    proc.stderr.on('data', (d) => {
      buf += d;
      const m = /DevTools listening on (ws:\/\/\S+)/.exec(buf);
      if (m) { clearTimeout(timer); resolve(m[1]); }
    });
    proc.once('exit', (code) => reject(new Error(`Chrome exited early (${code})`)));
  });
  const ws = new WebSocket(wsUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let nextId = 0;
  const pending = new Map();
  const listeners = new Set();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const p = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? p.reject(new Error(msg.error.message)) : p.resolve(msg.result);
    } else for (const l of listeners) l(msg);
  };
  const send = (method, params = {}, sessionId) => {
    const id = ++nextId;
    ws.send(JSON.stringify({ id, method, params, sessionId }));
    return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
  };

  async function openPage({ width, height, mobile = false, scale = 1, scheme, reducedMotion = false, touch = false }) {
    const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    const s = (method, params) => send(method, params, sessionId);
    const problems = [];
    const onEvent = (msg) => {
      if (msg.sessionId !== sessionId) return;
      if (msg.method === 'Runtime.exceptionThrown') {
        problems.push({ kind: 'exception', text: msg.params.exceptionDetails.exception?.description ?? msg.params.exceptionDetails.text });
      } else if (msg.method === 'Runtime.consoleAPICalled' && (msg.params.type === 'error' || msg.params.type === 'warning')) {
        problems.push({ kind: `console.${msg.params.type}`, text: msg.params.args.map((a) => a.value ?? a.description).join(' ') });
      } else if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
        problems.push({ kind: 'log', text: msg.params.entry.text, url: msg.params.entry.url ?? '' });
      }
    };
    listeners.add(onEvent);
    await s('Page.enable');
    await s('Runtime.enable');
    await s('Log.enable');
    await s('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile });
    if (touch) await s('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    const features = [];
    if (scheme) features.push({ name: 'prefers-color-scheme', value: scheme });
    if (reducedMotion) features.push({ name: 'prefers-reduced-motion', value: 'reduce' });
    if (features.length) await s('Emulation.setEmulatedMedia', { features });

    const evaluate = async (expression) => {
      const r = await s('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text);
      return r.result.value;
    };
    const waitFor = async (expression, timeout = 15000) => {
      const end = Date.now() + timeout;
      while (Date.now() < end) {
        if (await evaluate(expression).catch(() => false)) return true;
        await new Promise((r) => setTimeout(r, 50));
      }
      throw new Error(`timed out waiting for: ${expression}`);
    };
    /** Drags the mouse through client-space points, pausing so the brush sees real speed. */
    const drag = async (points, { stepMs = 8 } = {}) => {
      const [first, ...rest] = points;
      await s('Input.dispatchMouseEvent', { type: 'mouseMoved', x: first[0], y: first[1] });
      await s('Input.dispatchMouseEvent', { type: 'mousePressed', x: first[0], y: first[1], button: 'left', buttons: 1, clickCount: 1 });
      for (const [x, y] of rest) {
        await new Promise((r) => setTimeout(r, stepMs));
        await s('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'left', buttons: 1 });
      }
      const last = points[points.length - 1];
      await s('Input.dispatchMouseEvent', { type: 'mouseReleased', x: last[0], y: last[1], button: 'left', buttons: 0, clickCount: 1 });
    };
    /** The same with a touch pointer. */
    const touchDrag = async (points, { stepMs = 8 } = {}) => {
      const tp = ([x, y]) => [{ x, y, id: 1, radiusX: 4, radiusY: 4, force: 0.5 }];
      await s('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: tp(points[0]) });
      for (const p of points.slice(1)) {
        await new Promise((r) => setTimeout(r, stepMs));
        await s('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: tp(p) });
      }
      await s('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    };
    return {
      problems,
      evaluate,
      waitFor,
      drag,
      touchDrag,
      navigate: (url) => s('Page.navigate', { url }),
      send: s,
      screenshot: async () => Buffer.from((await s('Page.captureScreenshot', { format: 'png' })).data, 'base64'),
      close: async () => { listeners.delete(onEvent); await send('Target.closeTarget', { targetId }).catch(() => {}); },
    };
  }

  async function close() {
    try { ws.close(); } catch { /* already closed */ }
    proc.kill();
    await new Promise((r) => (proc.exitCode !== null ? r() : proc.once('exit', r)));
    rmSync(profile, { recursive: true, force: true });
  }
  return { openPage, close };
}
