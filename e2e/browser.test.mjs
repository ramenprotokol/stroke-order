// Headless-browser checks of the built site, served with its real _headers (CSP):
// real pointer input writes characters stroke by stroke and must earn the seal, with
// no console errors anywhere.
import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { serve } from '../scripts/serve.mjs';
import { browserPlan, findChrome, launchChrome } from './cdp.mjs';
import { centreSheet, dist, referenceStrokes } from './helpers.mjs';

const chromePath = findChrome();
const plan = browserPlan(chromePath);
const opts = plan.skip ? { skip: plan.skip } : {};
let server;
let chrome;
let base;

before(async () => {
  if (plan.fail) throw new Error(plan.fail);
  if (plan.skip) return;
  server = await serve(dist, 0);
  base = `http://127.0.0.1:${server.address().port}/`;
  chrome = await launchChrome(chromePath);
});

after(async () => {
  await chrome?.close();
  server?.close();
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function open(hash = '', view = { width: 1280, height: 800, scheme: 'light' }) {
  const page = await chrome.openPage(view);
  await page.send('Browser.setDownloadBehavior', { behavior: 'deny' }).catch(() => {});
  await page.navigate(base + hash);
  await page.waitFor(`document.body.dataset.ready === '1'`);
  await page.evaluate(centreSheet);
  await sleep(120);
  return page;
}

const feedback = (page) => page.evaluate(`document.getElementById('feedback').textContent`);
const state = (page) => page.evaluate(`[document.getElementById('sheet').dataset.state, document.getElementById('sheet').dataset.progress, document.getElementById('seal').classList.contains('stamped')]`);

function noProblems(page) {
  assert.deepEqual(page.problems, [], `console errors or exceptions: ${JSON.stringify(page.problems)}`);
}

test('writing 右 stroke by stroke with the mouse: order is checked, then the seal stamps', opts, async () => {
  const page = await open('#k=%E5%8F%B3');
  assert.equal(await page.evaluate(`document.getElementById('glyph').textContent`), '右');
  const ref = await page.evaluate(referenceStrokes('右'));
  assert.equal(ref.length, 5);

  // The horizontal first is the classic mistake (左 starts that way; 右 does not).
  await page.drag(ref[1]);
  await page.waitFor(`document.getElementById('feedback').textContent.startsWith("That's stroke 2")`);
  assert.equal(await feedback(page), "That's stroke 2 — stroke 1 comes first (the left sweep).");
  assert.deepEqual(await state(page), ['writing', '0/5', false]);

  // The sweep, drawn backwards.
  await page.drag([...ref[0]].reverse());
  await page.waitFor(`document.getElementById('feedback').textContent.startsWith('Right stroke, other way round')`);

  for (let i = 0; i < ref.length; i++) {
    await page.drag(ref[i]);
    await page.waitFor(`document.getElementById('sheet').dataset.progress === '${i + 1}/5'`);
  }
  assert.deepEqual(await state(page), ['complete', '5/5', true]);
  assert.equal(await feedback(page), 'All 5 strokes are in order.');
  // The ink layer really has ink on it.
  const inked = await page.evaluate(`(() => { const c = document.getElementById('layer-ink'); const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let n = 0; for (let i = 3; i < d.length; i += 4) if (d[i] > 200) n++; return n; })()`);
  assert.ok(inked > 2000, `ink pixels: ${inked}`);
  // Undo lifts the last stroke and the seal.
  await page.evaluate(`document.getElementById('btn-undo').click()`);
  assert.deepEqual(await state(page), ['writing', '4/5', false]);
  noProblems(page);
  await page.close();
});

test('a phone (400×860, touch): no sideways scroll, and 十 written by touch gets the seal', opts, async () => {
  const page = await open('#k=%E5%8D%81', { width: 400, height: 860, mobile: true, scale: 2, touch: true, scheme: 'dark' });
  const overflow = await page.evaluate(`document.documentElement.scrollWidth - window.innerWidth`);
  assert.ok(overflow <= 0, `page is ${overflow}px wider than the screen`);
  const ref = await page.evaluate(referenceStrokes('十'));
  for (const s of ref) {
    await page.touchDrag(s);
    await sleep(60);
  }
  await page.waitFor(`document.getElementById('sheet').dataset.state === 'complete'`);
  assert.equal(await feedback(page), 'All 2 strokes are in order, first time.');
  noProblems(page);
  await page.close();
});

test('bad and hostile share links show a clear message and the page still works', opts, async () => {
  const cases = [
    ['#k=%E9%BE%8D', /isn't in this set of 80/],
    ['#k=' + '%E7%81%AB'.repeat(400), /too long/],
    ['#k=%E7%81', /garbled/],
    ['#k=%3Cimg%20src%3Dx%20onerror%3Dalert(1)%3E', /one character/],
  ];
  for (const [hash, message] of cases) {
    const page = await open(hash);
    assert.match(await feedback(page), message, hash.slice(0, 40));
    assert.equal(await page.evaluate(`document.getElementById('glyph').textContent`), '一', 'falls back to the first character');
    assert.equal(await page.evaluate(`document.querySelectorAll('img').length`), 0);
    noProblems(page);
    await page.close();
  }
});

test('Show me: animated normally; with reduced motion, static numbered strokes at once', opts, async () => {
  const inkOn = `(() => { const c = document.getElementById('layer-demo'); const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let n = 0; for (let i = 3; i < d.length; i += 4) if (d[i] > 100) n++; return n; })()`;

  const still = await chrome.openPage({ width: 1280, height: 800, reducedMotion: true, scheme: 'light' });
  await still.navigate(base + '#k=%E7%81%AB');
  await still.waitFor(`document.body.dataset.ready === '1'`);
  await still.evaluate(`document.getElementById('btn-show').focus()`);
  await still.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r', unmodifiedText: '\r' });
  await still.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await sleep(50);
  assert.ok((await still.evaluate(inkOn)) > 3000, 'reduced motion: the whole character is shown at once');
  assert.equal(await still.evaluate(`document.getElementById('btn-show').textContent`), 'Show me', 'nothing is animating');
  noProblems(still);
  await still.close();

  const moving = await open('#k=%E7%81%AB');
  await moving.evaluate(`document.getElementById('btn-show').click()`);
  assert.equal(await moving.evaluate(`document.getElementById('btn-show').textContent`), 'Stop');
  await sleep(400);
  const early = await moving.evaluate(inkOn);
  await moving.waitFor(`document.getElementById('btn-show').textContent === 'Show me'`, 10000);
  const done = await moving.evaluate(inkOn);
  assert.ok(done > early, `the demo grows as it plays (${early} → ${done})`);

  // Cutting the demo short (writing a stroke, or moving to another character) puts the
  // button back to "Show me" rather than leaving a "Stop" with nothing playing.
  const label = () => moving.evaluate(`document.getElementById('btn-show').textContent`);
  await moving.evaluate(`document.getElementById('btn-show').click()`);
  assert.equal(await label(), 'Stop');
  await moving.drag((await moving.evaluate(referenceStrokes('火')))[0]);
  await moving.waitFor(`document.getElementById('sheet').dataset.progress === '1/4'`);
  assert.equal(await label(), 'Show me', 'writing a stroke stops the demo');
  assert.equal(await moving.evaluate(inkOn), 0, 'and wipes it');
  await moving.evaluate(`document.getElementById('btn-show').click()`);
  assert.equal(await label(), 'Stop');
  await moving.evaluate(`document.getElementById('btn-next').click()`);
  assert.equal(await label(), 'Show me', 'Next stops the demo');
  noProblems(moving);
  await moving.close();
});

test('search, toggles, paper, PNG save and link copy work without errors', opts, async () => {
  const page = await open();
  await page.evaluate(`(() => { const s = document.getElementById('search'); s.value = 'mizu'; s.dispatchEvent(new Event('input')); })()`);
  assert.deepEqual(await page.evaluate(`[...document.querySelectorAll('#results .char')].map((b) => b.textContent)`), ['水']);
  await page.evaluate(`(() => { const s = document.getElementById('search'); s.value = 'qqqq'; s.dispatchEvent(new Event('input')); })()`);
  assert.match(await page.evaluate(`document.getElementById('search-status').textContent`), /Nothing in this set matches/);
  await page.evaluate(`(() => { const s = document.getElementById('search'); s.value = 'x'.repeat(5000); s.dispatchEvent(new Event('input')); })()`);
  await page.evaluate(`(() => { const s = document.getElementById('search'); s.value = ''; s.dispatchEvent(new Event('input')); })()`);

  await page.evaluate(`document.querySelector('.char[aria-label^="森"]').click()`);
  assert.equal(await page.evaluate(`location.hash`), '#k=%E6%A3%AE');
  assert.equal(await page.evaluate(`document.getElementById('count').textContent`), '12 strokes');

  await page.evaluate(`document.getElementById('tgl-grid').click()`);
  assert.equal(await page.evaluate(`document.getElementById('tgl-grid').getAttribute('aria-pressed')`), 'false');
  await page.evaluate(`document.getElementById('tgl-paper').click()`);
  assert.equal(await page.evaluate(`document.documentElement.dataset.theme`), 'indigo');
  assert.equal(await page.evaluate(`getComputedStyle(document.body).backgroundColor`), 'rgb(13, 18, 32)');

  await page.evaluate(`document.getElementById('btn-save').click()`);
  await page.waitFor(`document.getElementById('share-note').textContent.startsWith('Saved')`);
  assert.equal(await page.evaluate(`document.getElementById('share-note').textContent`), 'Saved stroke-order-u68ee.png.');
  await page.evaluate(`document.getElementById('btn-link').click()`);
  await page.waitFor(`document.getElementById('share-note').textContent !== 'Saved stroke-order-u68ee.png.'`);
  assert.match(await page.evaluate(`document.getElementById('share-note').textContent`), /Link copied|#k=%E6%A3%AE/);
  noProblems(page);
  await page.close();
});
