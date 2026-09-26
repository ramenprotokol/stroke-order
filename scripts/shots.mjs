#!/usr/bin/env node
// Dev tool: screenshots of the built site (desktop 1280×800 and a true 400×860 phone via
// DevTools emulation), light and dark, mid-writing and sealed. Usage:
//   node scripts/shots.mjs <output dir> [character]
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { findChrome, launchChrome } from '../e2e/cdp.mjs';
import { centreSheet, dist, referenceStrokes } from '../e2e/helpers.mjs';
import { serve } from './serve.mjs';

const out = process.argv[2];
const char = process.argv[3] ?? '火';
if (!out) {
  console.error('usage: node scripts/shots.mjs <output dir> [character]');
  process.exit(2);
}
mkdirSync(out, { recursive: true });
const server = await serve(dist, 0);
const base = `http://127.0.0.1:${server.address().port}/`;
const chrome = await launchChrome(findChrome());
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shoot(name, opts, { strokes = Infinity, showMe = false, wrong = false, save = false } = {}) {
  const page = await chrome.openPage(opts);
  if (save) await page.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: out });
  await page.navigate(`${base}#k=${encodeURIComponent(char)}`);
  await page.waitFor(`document.body.dataset.ready === '1'`);
  await page.evaluate(`document.fonts.ready.then(() => true)`);
  await sleep(300);
  if (showMe) {
    await page.evaluate(`document.getElementById('btn-show').click()`);
    await sleep(opts.reducedMotion ? 300 : 6000);
  } else {
    await page.evaluate(centreSheet);
    await sleep(150);
    const ref = await page.evaluate(referenceStrokes(char));
    const n = Math.min(strokes, ref.length);
    for (let i = 0; i < n; i++) {
      await page.drag(ref[i], { stepMs: 6 });
      await sleep(80);
    }
    if (wrong && n < ref.length) {
      await page.drag(ref[ref.length - 1], { stepMs: 6 });
      await sleep(200);
    }
    await sleep(700);
    await page.evaluate(`window.scrollTo(0, 0)`);
    await sleep(100);
  }
  writeFileSync(join(out, `${name}.png`), await page.screenshot());
  if (save) {
    await page.evaluate(`document.getElementById('btn-save').click()`);
    await page.waitFor(`document.getElementById('share-note').textContent.startsWith('Saved')`);
    await sleep(800);
  }
  if (page.problems.length) console.log(name, 'problems:', page.problems);
  await page.close();
  console.log('wrote', name);
}

try {
  await shoot('desktop-light', { width: 1280, height: 800, scheme: 'light' }, { save: true });
  await shoot('desktop-dark', { width: 1280, height: 800, scheme: 'dark' }, { strokes: 2, wrong: true });
  await shoot('phone-light', { width: 400, height: 860, mobile: true, scale: 2, scheme: 'light' });
  await shoot('phone-dark', { width: 400, height: 860, mobile: true, scale: 2, scheme: 'dark' });
  await shoot('showme-static', { width: 1280, height: 800, reducedMotion: true, scheme: 'light' }, { showMe: true });
  await shoot('showme-anim', { width: 1280, height: 800, scheme: 'dark' }, { showMe: true });
} finally {
  await chrome.close();
  server.close();
}
