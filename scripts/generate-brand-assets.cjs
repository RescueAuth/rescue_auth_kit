#!/usr/bin/env node
/** Raster fallbacks / preview from the same SVG as the Android vectors. Requires the dev-only sharp package. */
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const design = path.join(root, 'design', 'brand');
const source = fs.readFileSync(path.join(design, 'rescueauth-symbol.svg'), 'utf8');
const paths = [...source.matchAll(/<path[^>]+\/>/g)].map(match => match[0]).join('\n');
if (!paths) throw new Error('SVG master contains no paths.');
const scale = Number(source.match(/data-launcher-scale="([\d.]+)"/)?.[1] ?? 0.49);
const iconScale = scale * 1024 / 108;
const offset = 512 - 64 * iconScale;
const mark = `<g transform="translate(${offset} ${offset}) scale(${iconScale})">${paths}</g>`;
const base = (round = false) => `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">${round ? '<circle cx="512" cy="512" r="512" fill="#242A38"/>' : '<rect width="1024" height="1024" rx="232" fill="#242A38"/>'}${mark}</svg>`;
const square = base();
fs.writeFileSync(path.join(design, 'rescueauth-app-icon.svg'), square);
async function render() {
    await sharp(Buffer.from(square)).resize(512, 512).png().toFile(path.join(design, 'rescueauth-app-icon.png'));
    for (const [density, size] of Object.entries({mdpi:48, hdpi:72, xhdpi:96, xxhdpi:144, xxxhdpi:192})) {
        const dir = path.join(root, 'app', 'src', 'main', 'res', `mipmap-${density}`);
        fs.mkdirSync(dir, {recursive:true});
        await sharp(Buffer.from(square)).resize(size, size).png().toFile(path.join(dir, 'ic_launcher.png'));
        await sharp(Buffer.from(base(true))).resize(size, size).png().toFile(path.join(dir, 'ic_launcher_round.png'));
    }
    process.stdout.write('Generated app icon preview and ten launcher fallbacks from SVG.\n');
}
render().catch(error => { process.stderr.write(String(error)); process.exit(1); });
