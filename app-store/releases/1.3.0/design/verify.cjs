#!/usr/bin/env node
'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const sharp = require('sharp');
const screenshots = path.resolve(__dirname, '../screenshots');
const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

async function main() {
  const manifest = JSON.parse(await fs.readFile(path.join(screenshots, 'manifest.json'), 'utf8'));
  const releaseSourceCommit = manifest.releaseSourceCommit || manifest.capturedSourceCommit;
  if (manifest.prototype || manifest.files.length !== 60 || !releaseSourceCommit) throw new Error('Expected all 60 final images with native capture provenance.');
  const nativeManifest = JSON.parse(await fs.readFile(path.join(screenshots, 'native-capture-manifest.json'), 'utf8'));
  if ((nativeManifest.releaseSourceCommit || nativeManifest.sourceCommit) !== releaseSourceCommit || nativeManifest.count !== 60) throw new Error('Native capture provenance does not match rendered source.');
  const capturedHashes = new Map(nativeManifest.screenshots.map((file) => [file.sha256, file.sourceCommit || nativeManifest.sourceCommit]));
  const groups = new Map();
  for (const file of manifest.files) {
    const bytes = await fs.readFile(path.join(screenshots, file.path));
    const meta = await sharp(bytes).metadata();
    const stats = await sharp(bytes).stats();
    if (meta.format !== 'png' || meta.width !== file.width || meta.height !== file.height || meta.hasAlpha || meta.space !== 'srgb') throw new Error(`Invalid output dimensions/color/alpha: ${file.path}`);
    if (sha256(bytes) !== file.outputSHA256) throw new Error(`Modified output: ${file.path}`);
    if (sha256(await fs.readFile(path.resolve(screenshots, '..', file.sourcePath))) !== file.sourceSHA256) throw new Error(`Native capture changed after rendering; re-render ${file.path}`);
    if (!capturedHashes.has(file.sourceSHA256)) throw new Error(`Source was not in the completed native capture manifest: ${file.path}`);
    if ((file.sourceCaptureCommit || manifest.capturedSourceCommit) !== capturedHashes.get(file.sourceSHA256)) throw new Error(`Source capture commit mismatch: ${file.path}`);
    if (stats.isOpaque !== true || stats.entropy < 1) throw new Error(`Blank/transparent output: ${file.path}`);
    if (file.layout.headlineOverflow || file.layout.copy.bottom >= file.layout.device.y) throw new Error(`Copy overflow: ${file.path}`);
    if (file.layout.device.bottom > file.height + 1) throw new Error(`Native device extends outside final canvas: ${file.path}`);
    const group = path.dirname(file.path);
    groups.set(group, [...(groups.get(group) || []), file]);
  }
  if (groups.size !== 10 || [...groups.values()].some((files) => files.length !== 6)) throw new Error('Expected six panels in each of 10 display/locale sets.');
  const qa = path.join(__dirname, 'qa');
  await fs.mkdir(qa, { recursive: true });
  for (const [name, files] of groups) {
    files.sort((a, b) => a.panel.localeCompare(b.panel));
    if (new Set(files.map((f) => f.sourceSHA256)).size !== 6) throw new Error(`Repeated native proof within ${name}`);
    const thumbWidth = name.startsWith('ipad-13') ? 450 : 350;
    const thumbHeight = Math.round(thumbWidth * files[0].height / files[0].width);
    const gap = 16;
    const images = await Promise.all(files.map(async (file, i) => ({ input: await sharp(path.join(screenshots, file.path)).resize(thumbWidth, thumbHeight).toBuffer(), left: (i % 3) * (thumbWidth + gap), top: Math.floor(i / 3) * (thumbHeight + gap) })));
    await sharp({ create: { width: 3 * thumbWidth + 2 * gap, height: 2 * thumbHeight + gap, channels: 3, background: '#dedede' } }).composite(images).png().toFile(path.join(qa, `${name.replaceAll('/', '-')}.png`));
  }
  const report = { checkedAt: new Date().toISOString(), releaseSourceCommit, captureSourceCommits: manifest.captureSourceCommits || [manifest.capturedSourceCommit], fileCount: manifest.files.length, setCount: groups.size, exactDimensions: true, opaqueRGB: true, sourceAndOutputChecksumsMatch: true, distinctNativeProofPerPanel: true, noHeadlineOverflow: true, fullDeviceInsideCanvas: true, contactSheets: [...groups.keys()].map((key) => `design/qa/${key.replaceAll('/', '-')}.png`), visualReview: 'See ../design/visual-review.md for the separate recorded visual review; not an automated assertion.' };
  await fs.writeFile(path.join(screenshots, 'verification.json'), `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
