#!/usr/bin/env node
'use strict';

// Local static-artifact rendering only. No live app, account, or network access.
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');
const sharp = require('sharp');

const designDir = __dirname;
const releaseDir = path.dirname(designDir);
const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const option = (name) => argv[argv.indexOf(name) + 1];
const prototype = flag('--prototype');
const nativeRoot = flag('--native-root') ? path.resolve(option('--native-root')) : path.join(releaseDir, 'native');
const releaseSHA = flag('--release-sha') ? option('--release-sha') : flag('--capture-sha') ? option('--capture-sha') : null;
const selectedFixtures = flag('--fixtures') ? new Set(option('--fixtures').split(',')) : null;
const outputRoot = path.resolve(flag('--output') ? option('--output') : prototype ? '/tmp/buddystudy-marketing-prototype' : path.join(releaseDir, 'screenshots'));
if (!prototype && (!nativeRoot || !/^[0-9a-f]{7,40}$/.test(releaseSHA || ''))) {
  throw new Error('Final rendering requires --release-sha for integrated 1.3.0 proof. Use --prototype for old-layout samples.');
}
if (prototype && outputRoot === path.join(releaseDir, 'screenshots')) {
  throw new Error('Prototype artwork must not be written into final screenshots.');
}

const devices = [
  { id: '6.5', width: 1242, height: 2688, locales: ['ko', 'en-US', 'ja'] },
  { id: '6.9', width: 1320, height: 2868, locales: ['ko', 'en-US', 'ja'] },
  { id: 'ipad-13', width: 2064, height: 2752, locales: ['ko', 'en-US', 'ja'] },
  { id: '6.3', width: 1206, height: 2622, locales: ['en-US'] },
];
const panels = [
  { id: '01-learning-result', fixture: 'learning-result', aliases: ['01-learning-result.png'], prototype: '01-learning-result.png' },
  { id: '02-follow-up', fixture: 'follow-up', aliases: ['02-follow-up.png'], prototype: '01-learning-result.png' },
  { id: '03-public-feed', fixture: 'feed', aliases: ['03-feed.png', '03-public-feed.png', '03-interest-feed.png'], prototype: '03-interest-feed.png' },
  { id: '04-study-tree', fixture: 'study-tree', aliases: ['04-study-tree.png'], prototype: '04-interest-topics.png' },
  { id: '05-topic-progress', fixture: 'statistics', aliases: ['05-statistics.png', '05-topic-progress.png', '02-topic-progress.png'], prototype: '02-topic-progress.png' },
  { id: '06-custom-question', fixture: 'custom-question', aliases: ['06-custom-question.png', '03-custom-question.png'], prototype: '01-learning-result.png' },
];
const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

async function sourceFor(device, locale, panel) {
  if (prototype) return path.join(releaseDir, '../1.2.0/screenshots', device.id, locale, panel.prototype);
  const directory = path.join(nativeRoot, device.id === 'ipad-13' ? device.id : `iphone-${device.id}`, locale);
  const candidates = [...new Set([`${panel.fixture}.png`, ...panel.aliases])];
  const found = [];
  for (const filename of candidates) {
    const file = path.join(directory, filename);
    try { await fs.access(file); found.push(file); } catch {}
  }
  if (found.length !== 1) throw new Error(`Expected exactly one native ${panel.fixture} capture in ${directory}; found ${found.length}. Allowed: ${candidates.join(', ')}`);
  return found[0];
}

async function main() {
  const copy = JSON.parse(await fs.readFile(path.join(designDir, 'copy.json'), 'utf8'));
  const nativeManifest = prototype ? null : JSON.parse(await fs.readFile(path.join(outputRoot, 'native-capture-manifest.json'), 'utf8'));
  const capturesByHash = new Map((nativeManifest?.screenshots || []).map((file) => [file.sha256, file]));
  const accentBytes = await fs.readFile(path.join(designDir, 'learning-tree-accent.png'));
  const accentURI = `data:image/png;base64,${accentBytes.toString('base64')}`;
  const tasks = [];
  // Validate all inputs before producing final output; never quietly substitute stale captures.
  for (const device of devices) for (const locale of device.locales) for (const [index, panel] of panels.entries()) {
    if (flag('--set') && option('--set') !== `${device.id}/${locale}`) continue;
    if (selectedFixtures && !selectedFixtures.has(panel.fixture)) continue;
    const source = await sourceFor(device, locale, panel);
    const bytes = await fs.readFile(source);
    const metadata = await sharp(bytes).metadata();
    const capture = capturesByHash.get(sha256(bytes));
    if (!prototype && !capture) throw new Error(`Native source missing from capture provenance: ${source}`);
    const sourceCaptureCommit = capture?.sourceCommit || nativeManifest?.sourceCommit || null;
    const ratio = metadata.width / metadata.height;
    if (device.id === 'ipad-13' ? ratio < 0.65 || ratio > 0.85 : ratio < 0.44 || ratio > 0.51) {
      throw new Error(`Unexpected native device aspect ratio: ${source} (${metadata.width} × ${metadata.height})`);
    }
    const text = copy[locale]?.[index];
    if (!text || text.length !== 3 || text.some((line) => !line.trim())) throw new Error(`Missing copy for ${locale}, ${panel.id}`);
    tasks.push({ device, locale, index, panel, source, sourceCaptureCommit, bytes, metadata, text });
  }
  if (!tasks.length) throw new Error('No matching screenshot set selected.');
  const browser = await chromium.launch({ headless: true, channel: process.env.SCREENSHOT_BROWSER_CHANNEL || 'chrome' });
  const output = [];
  const template = pathToFileURL(path.join(designDir, 'screenshot.html')).href;
  try {
    for (const task of tasks) {
      const { device, locale, index, panel, source, sourceCaptureCommit, bytes, metadata, text } = task;
      const page = await browser.newPage({ viewport: { width: device.width, height: device.height }, deviceScaleFactor: 1, locale, colorScheme: 'light' });
      // All assets are local/data URLs. Reject unexpected remote dependencies.
      await page.route(/^https?:/, (route) => route.abort());
      await page.goto(template);
      await page.evaluate(({ device, locale, index, text, nativeURI, accentURI, prototype }) => {
        const canvas = document.querySelector('.canvas');
        canvas.style.setProperty('--unit', `${device.width / 1242}px`);
        canvas.lang = locale;
        canvas.classList.add(`panel-${index + 1}`);
        if (index % 2 === 1) canvas.classList.add('blue');
        if ([0, 4].includes(index)) canvas.classList.add('has-accent');
        if (device.id === 'ipad-13') canvas.classList.add('tablet');
        document.documentElement.lang = locale;
        document.querySelector('#line1').textContent = text[0];
        document.querySelector('#line2').textContent = text[1];
        document.querySelector('#subtitle').textContent = text[2];
        document.querySelector('.native').src = nativeURI;
        document.querySelector('.accent').src = accentURI;
        document.querySelector('.prototype-label').hidden = !prototype;
      }, { device, locale, index, text, nativeURI: `data:image/png;base64,${bytes.toString('base64')}`, accentURI, prototype });
      await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].map((img) => img.decode())); });
      const layout = await page.evaluate(() => {
        const rect = (selector) => {
          const r = document.querySelector(selector).getBoundingClientRect();
          return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom };
        };
        const copy = rect('.copy');
        const device = rect('.device');
        const headlineOverflow = [...document.querySelectorAll('h1 span')].some((el) => el.scrollWidth > el.clientWidth + 1);
        return { copy, device, headlineOverflow, font: getComputedStyle(document.querySelector('h1')).fontFamily, headlineSize: getComputedStyle(document.querySelector('h1')).fontSize };
      });
      if (layout.headlineOverflow || layout.copy.bottom + device.width * 0.025 > layout.device.y) {
        throw new Error(`Copy collision/overflow in ${device.id}/${locale}/${panel.id}: ${JSON.stringify(layout)}`);
      }
      const targetDirectory = path.join(outputRoot, device.id, locale);
      await fs.mkdir(targetDirectory, { recursive: true });
      const filename = path.join(targetDirectory, `${panel.id}.png`);
      const rendered = await page.screenshot({ type: 'png', omitBackground: false });
      // App Store PNGs: opaque, sRGB, losslessly encoded at exact target dimensions.
      const finalPNG = await sharp(rendered).removeAlpha().toColorspace('srgb').png({ compressionLevel: 9 }).toBuffer();
      await fs.writeFile(filename, finalPNG);
      output.push({ path: path.relative(outputRoot, filename), width: device.width, height: device.height, locale, panel: panel.id, fixture: panel.fixture, sourcePath: path.relative(releaseDir, source), sourceCaptureCommit, sourceWidth: metadata.width, sourceHeight: metadata.height, sourceSHA256: sha256(bytes), outputSHA256: sha256(finalPNG), copy: text, layout });
      await page.close();
      console.log(`${prototype ? 'PROTOTYPE ' : ''}${path.relative(outputRoot, filename)}`);
    }
    let files = output;
    if (selectedFixtures && !flag('--set')) {
      const previous = JSON.parse(await fs.readFile(path.join(outputRoot, 'manifest.json'), 'utf8'));
      const replacements = new Map(output.map((file) => [file.path, file]));
      files = previous.files.map((file) => replacements.get(file.path) || { ...file, sourceCaptureCommit: capturesByHash.get(file.sourceSHA256)?.sourceCommit || previous.capturedSourceCommit || nativeManifest?.sourceCommit });
      if (files.length !== 60 || output.some((file) => !previous.files.some((old) => old.path === file.path))) throw new Error('Partial rendering requires an existing complete 60-file manifest.');
    }
    const manifest = { version: '1.3.0', prototype, releaseSourceCommit: releaseSHA, captureSourceCommits: [...new Set(files.map((file) => file.sourceCaptureCommit).filter(Boolean))], generatedAt: new Date().toISOString(), renderer: 'local Chromium + HTML/CSS; native image composited unchanged', chromiumVersion: browser.version(), panelOrder: panels.map((p) => p.id), accentSHA256: sha256(accentBytes), files };
    await fs.writeFile(path.join(outputRoot, flag('--set') ? `manifest-${option('--set').replaceAll('/', '-')}.json` : 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
    console.log(`Rendered ${output.length} images. ${outputRoot}`);
  } finally { await browser.close(); }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
