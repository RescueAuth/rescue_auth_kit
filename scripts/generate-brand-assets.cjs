#!/usr/bin/env node
/** Runtime density icons plus ignored build/brand-assets exports. Requires dev-only sharp. */
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const design = path.join(root, 'design', 'brand');
const output = path.join(root, 'build', 'brand-assets');
fs.mkdirSync(output, {recursive:true});
const source = fs.readFileSync(path.join(design, 'shiyifang-logo.svg'), 'utf8');
const defs = source.match(/<defs>[\s\S]*?<\/defs>/)?.[0] ?? '';
const paths = [...source.matchAll(/<path[^>]+\/>/g)].map(match => match[0]).join('\n');
if (!paths) throw new Error('SVG master contains no paths.');
const scale = Number(source.match(/data-launcher-scale="([\d.]+)"/)?.[1] ?? 0.49);
const backgroundStart = source.match(/data-launcher-background-start="(#[\dA-Fa-f]{6})"/)?.[1] ?? '#F7F5EF';
const backgroundEnd = source.match(/data-launcher-background-end="(#[\dA-Fa-f]{6})"/)?.[1] ?? backgroundStart;
const iconScale = scale * 1024 / 108;
const offset = 512 - 64 * iconScale;
const mark = `<g transform="translate(${offset} ${offset}) scale(${iconScale})">${paths}</g>`;
const base = (mask = null, viewportDp = 108) => {
    const size = 1024 * viewportDp / 108;
    const inset = (1024 - size) / 2;
    const shape = mask === 'round' ? `<circle cx="512" cy="512" r="${size / 2}"/>`
        : `<rect x="${inset}" y="${inset}" width="${size}" height="${size}" rx="${size * 232 / 1024}"/>`;
    const maskDef = mask ? `<defs><clipPath id="launcher-preview-mask">${shape}</clipPath></defs>` : '';
    const background = `<defs><linearGradient id="launcher-background" x1="0" y1="0" x2="1024" y2="1024" gradientUnits="userSpaceOnUse"><stop stop-color="${backgroundStart}"/><stop offset="1" stop-color="${backgroundEnd}"/></linearGradient></defs>`;
    const layers = `<rect width="1024" height="1024" fill="url(#launcher-background)"/>${mark}`;
    return `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="${inset} ${inset} ${size} ${size}">${defs}${background}${maskDef}${mask ? `<g clip-path="url(#launcher-preview-mask)">${layers}</g>` : layers}</svg>`;
};
const square = base();
fs.writeFileSync(path.join(output, 'rescueauth-app-icon.svg'), square);
async function render() {
    await sharp(Buffer.from(square)).resize(512, 512).png().toFile(path.join(output, 'rescueauth-app-icon.png'));
    for (const mask of ['rounded', 'round']) {
        const preview = base(mask);
        const name = `rescueauth-app-icon-${mask}-preview`;
        fs.writeFileSync(path.join(output, `${name}.svg`), preview);
        await sharp(Buffer.from(preview)).resize(512,512).png().toFile(path.join(output, `${name}.png`));
        // Preview a conservative centered launcher crop, not the entire 108dp source canvas.
        const adaptive = base(mask, 66);
        const adaptiveName = `rescueauth-adaptive-${mask}-preview`;
        fs.writeFileSync(path.join(output, `${adaptiveName}.svg`), adaptive);
        await sharp(Buffer.from(adaptive)).resize(512,512).png().toFile(path.join(output, `${adaptiveName}.png`));
    }
    for (const [density, size] of Object.entries({mdpi:48, hdpi:72, xhdpi:96, xxhdpi:144, xxxhdpi:192})) {
        const dir = path.join(root, 'app', 'src', 'main', 'res', `mipmap-${density}`);
        fs.mkdirSync(dir, {recursive:true});
        await sharp(Buffer.from(square)).resize(size, size).png().toFile(path.join(dir, 'ic_launcher.png'));
        // Legacy round fallback only; API 26+ uses the unmasked adaptive XML layers.
        await sharp(Buffer.from(base('round'))).resize(size, size).png().toFile(path.join(dir, 'ic_launcher_round.png'));
    }
    process.stdout.write('Generated full-square exports, separate mask previews, and launcher fallbacks.\n');
}
render().catch(error => { process.stderr.write(String(error)); process.exit(1); });
