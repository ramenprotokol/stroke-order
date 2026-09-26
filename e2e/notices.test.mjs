// Licence notices: dist/THIRD-PARTY-NOTICES.txt covers everything third-party that
// ships, and the page credits KanjiVG and links the notices.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { dist, root } from './helpers.mjs';

const path = join(dist, 'THIRD-PARTY-NOTICES.txt');

test('dist/THIRD-PARTY-NOTICES.txt exists', () => {
  assert.ok(existsSync(path));
});

test('it covers KanjiVG under CC BY-SA 3.0 with attribution and share-alike', () => {
  const t = readFileSync(path, 'utf8');
  assert.match(t, /KanjiVG/);
  assert.match(t, /Ulrich Apel/);
  assert.match(t, /https:\/\/kanjivg\.tagaini\.net/);
  assert.match(t, /CC BY-SA 3\.0/);
  assert.match(t, /https:\/\/creativecommons\.org\/licenses\/by-sa\/3\.0\//);
  assert.match(t, /release r\d{8}/);
  assert.match(t, /share-alike/i);
});

test('it covers the Kotlin stdlib, webpack runtime and both fonts, with licence texts', () => {
  const t = readFileSync(path, 'utf8');
  assert.match(t, /Kotlin standard library/);
  assert.match(t, /Apache License\s+Version 2\.0, January 2004/, 'full Apache-2.0 text');
  assert.match(t, /Kotlin Compiler\s+Copyright 2010-2024 JetBrains/, 'Kotlin NOTICE');
  assert.match(t, /Boost Software License - Version 1\.0/);
  assert.match(t, /webpack[\s\S]*Permission is hereby granted, free of charge/, 'webpack MIT text');
  assert.equal((t.match(/SIL OPEN FONT LICENSE Version 1\.1/g) ?? []).length, 2, 'OFL text for each font');
  assert.match(t, /Noto Serif JP/);
  assert.match(t, /Noto Sans/);
  // every file it names is really in dist/
  for (const m of t.matchAll(/->\s+(assets\/\S+)/g)) assert.ok(existsSync(join(dist, m[1])), m[1]);
});

test('the page credits KanjiVG and links the notices; the repo keeps the licences apart', () => {
  const html = readFileSync(join(dist, 'index.html'), 'utf8');
  assert.match(html, /href="https:\/\/kanjivg\.tagaini\.net\/"/);
  assert.match(html, /Ulrich Apel/);
  assert.match(html, /href="https:\/\/creativecommons\.org\/licenses\/by-sa\/3\.0\/"/);
  assert.match(html, /href="THIRD-PARTY-NOTICES\.txt"/);
  assert.match(readFileSync(join(root, 'LICENSE'), 'utf8'), /MIT License[\s\S]*Copyright \(c\) 2026 ramenprotokol/);
  assert.match(readFileSync(join(root, 'data', 'LICENSE'), 'utf8'), /CC BY-SA 3\.0/);
  assert.match(readFileSync(join(root, 'README.md'), 'utf8'), /THIRD-PARTY-NOTICES/);
});
