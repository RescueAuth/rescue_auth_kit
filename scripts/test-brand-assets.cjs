/** Canonical icon exports must be square and full-bleed; masks belong to previews/the OS. */
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');

async function assertFullSquare(file, size) {
    const {data, info} = await sharp(file).ensureAlpha().raw().toBuffer({resolveWithObject: true});
    assert.equal(info.width, size);
    assert.equal(info.height, size);
    for (let p = 3; p < data.length; p += 4) {
        assert.equal(data[p], 255, `Unexpected transparent pixel in ${file}`);
    }
}

test('standard app-icon export has a full opaque square background', async () => {
    await assertFullSquare(path.join(root, 'build/brand-assets/rescueauth-app-icon.png'), 512);
});

test('generic density fallbacks do not bake a rounded mask into the source', async () => {
    for (const [density, size] of Object.entries({mdpi:48, hdpi:72, xhdpi:96, xxhdpi:144, xxxhdpi:192})) {
        await assertFullSquare(path.join(root, `app/src/main/res/mipmap-${density}/ic_launcher.png`), size);
    }
});

test('adaptive background remains an unmasked rectangle', () => {
    const background = fs.readFileSync(path.join(root, 'app/src/main/res/drawable/ic_rescueauth_launcher_background.xml'), 'utf8');
    assert.match(background, /android:shape="rectangle"/);
    assert.doesNotMatch(background, /<corners|android:radius/);
    for (const name of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
        const adaptive = fs.readFileSync(path.join(root, 'app/src/main/res/mipmap-anydpi-v26', name), 'utf8');
        assert.match(adaptive, /<adaptive-icon/);
        assert.match(adaptive, /@drawable\/ic_rescueauth_launcher_background/);
    }
});
