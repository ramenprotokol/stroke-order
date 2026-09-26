#!/usr/bin/env node
// Builds dist/ from a clean clone:
//   1. verifies the committed KanjiVG snapshot against its checksums (no network),
//   2. builds the stroke-data file,
//   3. runs the Kotlin/JS production build (Gradle, webpack production mode + DCE),
//   4. assembles dist/ with content-hashed assets, _headers and THIRD-PARTY-NOTICES.txt.
// Pass --no-gradle to reuse the last Kotlin build output.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { brotliCompressSync, constants, gzipSync } from 'node:zlib';
import { ATTRIBUTION, buildData } from './build-data.mjs';
import { KANJIVG_TAG } from './fetch-kanjivg.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const web = join(root, 'web');

function run(cmd, args) {
  const r = spawnSync(cmd, args, { cwd: root, stdio: 'inherit' });
  if (r.status !== 0) {
    console.error(`${cmd} ${args.join(' ')} failed (${r.status})`);
    process.exit(1);
  }
}

// 1 + 2: data
run(process.execPath, [join(root, 'scripts', 'fetch-kanjivg.mjs'), '--verify']);
const { json: dataJson } = buildData();

// 3: Kotlin/JS
const bundle = join(root, 'build', 'dist', 'js', 'productionExecutable', 'app.js');
if (!process.argv.includes('--no-gradle')) {
  const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  run(gradlew, ['jsBrowserDistribution', '--console=plain', '--quiet']);
}
if (!existsSync(bundle)) {
  console.error('No Kotlin/JS bundle found; run without --no-gradle.');
  process.exit(1);
}

// 4: assemble
rmSync(dist, { recursive: true, force: true });
mkdirSync(join(dist, 'assets'), { recursive: true });
const hashed = new Map();
function emit(name, buf) {
  const ext = extname(name);
  const out = `assets/${name.slice(0, -ext.length)}.${createHash('sha256').update(buf).digest('hex').slice(0, 10)}${ext}`;
  writeFileSync(join(dist, out), buf);
  hashed.set(name, out);
  return out;
}

const fontSerif = emit('serif-jp.woff2', readFileSync(join(web, 'fonts', 'serif-jp.woff2')));
const fontSans = emit('sans-latin.woff2', readFileSync(join(web, 'fonts', 'sans-latin.woff2')));
// Font URLs in the stylesheet are relative to assets/.
const css = readFileSync(join(web, 'style.css'), 'utf8')
  .replaceAll('{{FONT_SERIF}}', fontSerif.replace('assets/', ''))
  .replaceAll('{{FONT_SANS}}', fontSans.replace('assets/', ''));
const cssOut = emit('style.css', Buffer.from(css));
const jsOut = emit('app.js', readFileSync(bundle));
const dataOut = emit('kanji-data.json', Buffer.from(dataJson));
const iconOut = emit('favicon.svg', readFileSync(join(web, 'favicon.svg')));

let html = readFileSync(join(web, 'index.html'), 'utf8');
const vars = { CSS: cssOut, JS: jsOut, DATA: dataOut, FAVICON: iconOut, FONT_SERIF: fontSerif };
for (const [k, v] of Object.entries(vars)) html = html.replaceAll(`{{${k}}}`, v);
if (/\{\{\w+\}\}/.test(html)) throw new Error('unreplaced placeholder in index.html');
writeFileSync(join(dist, 'index.html'), html);

// The one inline script (applies the saved paper before first paint) is allowed by hash.
const inline = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => `'sha256-${createHash('sha256').update(m[1]).digest('base64')}'`);
const csp = [
  "default-src 'self'",
  `script-src 'self' ${inline.join(' ')}`.trim(),
  "style-src 'self'",
  "font-src 'self'",
  "img-src 'self' blob:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join('; ');
writeFileSync(
  join(dist, '_headers'),
  `/*
  Content-Security-Policy: ${csp}
  X-Content-Type-Options: nosniff
  Referrer-Policy: no-referrer
  Permissions-Policy: camera=(), microphone=(), geolocation=()

/assets/*
  Cache-Control: public, max-age=31536000, immutable

/
  Cache-Control: no-cache

/index.html
  Cache-Control: no-cache
`,
);

// Third-party notices for everything in dist/ that is not our own code.
const kotlinVersion = /kotlin\("multiplatform"\) version "([^"]+)"/.exec(readFileSync(join(root, 'build.gradle.kts'), 'utf8'))[1];
const webpackVersion = JSON.parse(readFileSync(join(root, 'build', 'js', 'node_modules', 'webpack', 'package.json'), 'utf8')).version;
const lic = (f) => readFileSync(join(root, 'licenses', f), 'utf8').trim();
const ofl = (f) => readFileSync(join(web, 'fonts', f), 'utf8').trim();
const rule = '='.repeat(78);
const notices = `THIRD-PARTY NOTICES — stroke-order

stroke-order's own code is MIT-licensed (see LICENSE in the source repository).
This site also ships the third-party code, data and fonts listed below.

${rule}
1. KanjiVG stroke data  ->  ${dataOut}
${rule}
Name:      KanjiVG (Kanji Vector Graphics)
Version:   release ${KANJIVG_TAG}
Copyright: Copyright (C) 2009/2010/2011 Ulrich Apel
Licence:   Creative Commons Attribution-Share Alike 3.0 Unported (CC BY-SA 3.0)
           https://creativecommons.org/licenses/by-sa/3.0/
Source:    https://kanjivg.tagaini.net  (https://github.com/KanjiVG/kanjivg)

${ATTRIBUTION}

Because CC BY-SA is share-alike, that data file (an adaptation of KanjiVG) is
licensed under CC BY-SA 3.0 as well, not under the app's MIT licence. You may
copy, adapt and share it under the terms of CC BY-SA 3.0. It is provided as is,
without warranties; see the licence for details.

${rule}
2. Kotlin standard library (compiled into ${jsOut})
${rule}
Name:      Kotlin standard library for JavaScript (kotlin-stdlib, kotlin-dom-api-compat)
Version:   ${kotlinVersion}
Copyright: Copyright 2010-2024 JetBrains s.r.o and respective authors and developers
Licence:   Apache License 2.0 (full text in section 6)
Source:    https://github.com/JetBrains/kotlin

Kotlin's NOTICE file:
${lic('kotlin-NOTICE.txt')}

The Kotlin project lists these origins for parts of the JavaScript standard
library (license/README.md in the Kotlin repository). Whatever survives
dead-code elimination is inside ${jsOut}:
  - collections: derived from GWT, (C) 2007-08 Google Inc. — Apache License 2.0
  - Long arithmetic: derived from the Google Closure Library, Copyright 2009
    The Closure Library Authors — Apache License 2.0
  - unsigned numbers: derived from Guava's UnsignedLongs, (C) 2011 The Guava
    Authors — Apache License 2.0
  - math polyfills: derived from boost special math functions, Copyright Eric
    Ford & Hubert Holin 2001 — Boost Software License 1.0 (section 7)
  - kotlin.time: Copyright (c) 2007-present, Stephen Colebourne & Michael
    Nascimento Santos — BSD 3-Clause (section 8)

${rule}
3. webpack runtime (module wrapper in ${jsOut})
${rule}
Name:      webpack
Version:   ${webpackVersion}
Licence:   MIT
Source:    https://github.com/webpack/webpack

${lic('MIT-webpack.txt')}

${rule}
4. Noto Serif JP (subset, weight 500)  ->  ${fontSerif}
${rule}
Name:      Noto Serif JP, subset to the characters this app shows
Version:   2.003
Licence:   SIL Open Font License 1.1
Source:    https://github.com/google/fonts/tree/main/ofl/notoserifjp

${ofl('OFL-NotoSerifJP.txt')}

${rule}
5. Noto Sans (Latin subset)  ->  ${fontSans}
${rule}
Name:      Noto Sans, subset to Latin
Version:   2.015
Licence:   SIL Open Font License 1.1
Source:    https://github.com/google/fonts/tree/main/ofl/notosans

${ofl('OFL-NotoSans.txt')}

${rule}
6. Apache License 2.0 (full text)
${rule}
${lic('Apache-2.0.txt')}

${rule}
7. Boost Software License 1.0 (full text)
${rule}
${lic('BSL-1.0-boost.txt')}

${rule}
8. BSD 3-Clause licence (ThreeTen, full text)
${rule}
${lic('BSD-3-Clause-threetenbp.txt')}
`;
writeFileSync(join(dist, 'THIRD-PARTY-NOTICES.txt'), notices);

// Report sizes (measured, not estimated).
function walk(dir) {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}
const files = walk(dist);
let total = 0;
console.log('\ndist/');
for (const f of files.sort()) {
  const buf = readFileSync(f);
  total += buf.length;
  const gz = gzipSync(buf, { level: 9 }).length;
  const br = brotliCompressSync(buf, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length;
  console.log(`  ${relative(dist, f).padEnd(40)} ${String(buf.length).padStart(8)} B   gzip ${String(gz).padStart(7)}   brotli ${String(br).padStart(7)}`);
}
console.log(`  ${files.length} files, ${total} bytes total (Pages limits: 20,000 files, 25 MiB per file)`);
