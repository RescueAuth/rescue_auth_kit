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
const iconScale = scale * 1024 / 108;
const offset = 512 - 64 * iconScale;
const mark = `<g transform="translate(${offset} ${offset}) scale(${iconScale})">${paths}</g>`;
const base = (mask = null) => {
    const shape = mask === 'round' ? '<circle cx="512" cy="512" r="512"/>'
        : '<rect width="1024" height="1024" rx="232"/>';
    const maskDef = mask ? `<defs><clipPath id="launcher-preview-mask">${shape}</clipPath></defs>` : '';
    const layers = `<rect width="1024" height="1024" fill="#F7F5EF"/>${mark}`;
    return `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">${defs}${maskDef}${mask ? `<g clip-path="url(#launcher-preview-mask)">${layers}</g>` : layers}</svg>`;
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
