// When the page can't start, it says why: a failed download, unreadable data and a
// failure while starting each get their own honest message.
import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readdirSync, rmSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import { serve } from '../scripts/serve.mjs';
import { browserPlan, findChrome, launchChrome } from './cdp.mjs';
import { dist } from './helpers.mjs';

const chromePath = findChrome();
const plan = browserPlan(chromePath);
const opts = plan.skip ? { skip: plan.skip } : {};
let chrome;
const cleanup = [];

before(async () => {
  if (plan.fail) throw new Error(plan.fail);
  if (!plan.skip) chrome = await launchChrome(chromePath);
});

after(async () => {
  await chrome?.close();
  for (const f of cleanup) f();
});

/** Serves a copy of dist/ changed by [edit]; returns its base URL. */
async function variant(edit) {
  const dir = mkdtempSync(join(tmpdir(), 'stroke-order-dist-'));
  cpSync(dist, dir, { recursive: true });
  const data = join(dir, 'assets', readdirSync(join(dir, 'assets')).find((f) => f.startsWith('kanji-data.')));
  edit(data);
  const server = await serve(dir, 0);
  cleanup.push(() => { server.close(); rmSync(dir, { recursive: true, force: true }); });
  return `http://127.0.0.1:${server.address().port}/`;
}

async function failure(base, before) {
  const page = await chrome.openPage({ width: 1280, height: 800, scheme: 'light' });
  if (before) await page.send('Page.addScriptToEvaluateOnNewDocument', { source: before });
  await page.navigate(base);
  await page.waitFor(`document.body.dataset.ready === 'error'`);
  const text = await page.evaluate(`document.getElementById('feedback').textContent`);
  await page.close();
  return text;
}

test('missing stroke data: says it could not be loaded', opts, async () => {
  const text = await failure(await variant((data) => unlinkSync(data)));
  assert.match(text, /^The stroke data couldn't be loaded \(the server answered 404\)/);
});

test('corrupt stroke data: says it arrived but could not be read', opts, async () => {
  const text = await failure(await variant((data) => writeFileSync(data, '{"format": 1, "groups": [')));
  assert.match(text, /^The stroke data arrived but couldn't be read \(stroke data is not valid JSON\)/);
});

test('an error while starting is not blamed on the data', opts, async () => {
  // Break something the page needs after the data has loaded.
  const text = await failure(await variant(() => {}), 'window.matchMedia = undefined;');
  assert.match(text, /^Sorry — the page hit an error while starting/);
  assert.doesNotMatch(text, /stroke data/);
});
