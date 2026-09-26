// The data build: the curated subset exists in the pinned KanjiVG snapshot, the built
// file's stroke counts match KanjiVG's, and the file carries its licence.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { buildData, parseKanjiVg } from '../scripts/build-data.mjs';
import { codeOf, curatedChars, readSums, sha256 } from '../scripts/fetch-kanjivg.mjs';
import { root } from './helpers.mjs';

// Standard stroke counts for the 80 grade-1 kanji, written down independently of
// KanjiVG as a cross-check.
const STANDARD = {
  一: 1, 二: 2, 三: 3, 四: 5, 五: 4, 六: 4, 七: 2, 八: 2, 九: 2, 十: 2, 百: 6, 千: 3, 円: 4,
  日: 4, 月: 4, 火: 4, 水: 4, 木: 4, 金: 8, 土: 3, 山: 3, 川: 3, 田: 5, 石: 5, 林: 8, 森: 12,
  花: 7, 草: 9, 竹: 6, 雨: 8, 空: 8, 天: 4, 気: 6, 夕: 3, 虫: 6, 犬: 4, 貝: 7,
  人: 2, 子: 3, 女: 3, 男: 7, 王: 4, 口: 3, 目: 5, 耳: 6, 手: 4, 足: 7, 力: 2, 名: 6, 先: 6, 生: 5,
  大: 3, 小: 3, 中: 4, 上: 3, 下: 3, 左: 5, 右: 5, 本: 5, 文: 4, 字: 6, 学: 8, 校: 10, 年: 6,
  早: 6, 白: 5, 赤: 7, 青: 8, 車: 7, 町: 7, 村: 7, 入: 2, 出: 5, 立: 5, 休: 6, 見: 7, 音: 9,
  糸: 6, 正: 5, 玉: 5,
};

const svgOf = (c) => readFileSync(join(root, 'data', 'kanjivg', `${codeOf(c)}.svg`), 'utf8');

test('the curated subset is the 80 grade-1 kanji and every one is in the snapshot', () => {
  const chars = curatedChars();
  assert.equal(chars.length, 80);
  assert.equal(new Set(chars).size, 80, 'no duplicates');
  assert.deepEqual([...chars].sort(), Object.keys(STANDARD).sort());
  const sums = readSums();
  for (const c of chars) {
    const file = `${codeOf(c)}.svg`;
    const buf = readFileSync(join(root, 'data', 'kanjivg', file));
    assert.equal(sha256(buf), sums.get(file), `${c}: checksum`);
    assert.match(buf.toString('utf8'), /Ulrich Apel/, `${c}: KanjiVG copyright header kept`);
  }
});

test('stroke counts in the built file match KanjiVG (and the standard counts)', () => {
  const { data } = buildData();
  const built = new Map(data.groups.flatMap((g) => g.kanji).map((k) => [k.c, k]));
  assert.equal(built.size, 80);
  for (const [c, standard] of Object.entries(STANDARD)) {
    const k = built.get(c);
    assert.ok(k, `${c} is in the built file`);
    const kanjivgPaths = (svgOf(c).match(/<path /g) ?? []).length;
    assert.equal(k.s.length, kanjivgPaths, `${c}: built strokes vs KanjiVG paths`);
    assert.equal(k.s.length, standard, `${c}: KanjiVG vs the standard count`);
    for (const s of k.s) {
      assert.match(s.d, /^M/, `${c}: path data`);
      assert.ok(Array.isArray(s.n) && s.n.length === 2, `${c}: stroke-number position`);
    }
  }
});

test('strokes come out in KanjiVG order (by the -sN ids), not file order', () => {
  const svg = '<path id="kvg:04e8c-s2" kvg:type="㇐" d="M2,2"/><path id="kvg:04e8c-s1" kvg:type="㇐" d="M1,1"/>';
  assert.deepEqual(parseKanjiVg(svg, '二').map((s) => s.d), ['M1,1', 'M2,2']);
  assert.throws(() => parseKanjiVg('<path id="kvg:04e8c-s1" d="M1,1"/><path id="kvg:04e8c-s3" d="M1,1"/>', '二'), /stroke numbers/);
  assert.throws(() => parseKanjiVg('<path id="kvg:0706b-s1" d="M1,1"/>', '二'), /unexpected path id/);
});

test('the data file names KanjiVG, its author and CC BY-SA 3.0, and stays small', () => {
  const { data, json } = buildData();
  assert.match(data.attribution, /KanjiVG/);
  assert.match(data.attribution, /Ulrich Apel/);
  assert.match(data.attribution, /kanjivg\.tagaini\.net/);
  assert.match(data.license, /CC BY-SA 3\.0/);
  assert.match(data.license, /creativecommons\.org\/licenses\/by-sa\/3\.0/);
  assert.ok(Buffer.byteLength(json) < 200 * 1024, 'well under Pages’ 25 MiB per file');
});
