# RescueAuth SVG identity

`rescueauth-symbol.svg` is the single source for the in-app brand mark and Android launcher icon.
It contains solid filled paths in a 128 × 128 viewBox. There are no fonts, bitmap images, filters,
external links or scripts inside the SVG.

The selected mark is **Folded Shield**, retaining the original folded outline with an open center.
`launcher-preview.png` is the Android 15 launcher capture using the generated adaptive icon.

Earlier abstract studies are retained in `candidates/`:

- `verification-loop.svg`: an open rhythm and an ascending verification stroke.
- `folded-a.svg`: an abstract A assembled from two geometric ribbons.
- `folded-shield.svg`: the selected folded outline with an open center.

`icon-options.svg` / `icon-options.png` compare the two directions at app-icon and small sizes.
`rescueauth-app-icon.svg` places the selected symbol on the ink background used by the launcher.

## Regeneration

Run `python3 scripts/generate-brand-vectors.py` to rebuild the two in-app VectorDrawables and two
adaptive launcher layers. `python3 scripts/generate-brand-vectors.py --check` detects SVG/XML drift.
The SVG's `data-launcher-scale` sets the scale inside Android's adaptive-icon safe area.

For the legacy density-specific PNG fallbacks and the 512 px icon preview, run
`node scripts/generate-brand-assets.cjs` in a development environment with `sharp` available.
This package is a development renderer only; it is not an Android runtime dependency.

The colored symbol is used on ink panels. The same geometry, tinted from the active theme, is
used beside the wordmark and on light surfaces. Android's themed icon uses the generated
monochrome foreground. Application ID, signing identity and application labels do not change.
