#!/usr/bin/env python3
"""Build Android VectorDrawables from the one public SVG master; no SVG library required."""
from pathlib import Path
import argparse
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'design/brand/rescueauth-symbol.svg'
DEST = ROOT / 'app/src/main/res/drawable'
ANDROID = 'http://schemas.android.com/apk/res/android'
SVG = 'http://www.w3.org/2000/svg'


def outputs():
    source = ET.parse(SOURCE).getroot()
    if source.get('viewBox') != '0 0 128 128':
        raise ValueError('The brand master must keep a 128 by 128 viewBox.')
    launcher_scale = float(source.get('data-launcher-scale', '0.49'))
    if not 0 < launcher_scale <= 0.6:
        raise ValueError('Invalid adaptive launcher scale.')
    launcher_offset = 54 - 64 * launcher_scale
    paths = source.findall(f'{{{SVG}}}path')
    if not paths or any(path.get('d') is None or path.get('fill') is None for path in paths):
        raise ValueError('Only explicitly filled SVG paths are supported.')

    def vector(launcher=False, mono=False):
        size = 108 if launcher else 128
        xml = ['<?xml version="1.0" encoding="utf-8"?>',
               '<!-- Generated from design/brand/rescueauth-symbol.svg. Edit the SVG, then run scripts/generate-brand-vectors.py. -->',
               f'<vector xmlns:android="{ANDROID}" android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">']
        # All outer control points stay inside the adaptive icon's 66 dp safe circle.
        if launcher:
            xml.append(f'    <group android:scaleX="{launcher_scale:g}" android:scaleY="{launcher_scale:g}" android:translateX="{launcher_offset:g}" android:translateY="{launcher_offset:g}">')
        for path in paths:
            color = '#FFFFFF' if mono else path.get('fill')
            xml.append(f'        <path android:fillColor="{color}" android:pathData="{path.get("d")}"/>')
        if launcher:
            xml.append('    </group>')
        xml.append('</vector>\n')
        return '\n'.join(xml)

    return {
        'ic_rescueauth_mark.xml': vector(),
        'ic_rescueauth_mark_mono.xml': vector(mono=True),
        'ic_rescueauth_launcher_foreground.xml': vector(launcher=True),
        'ic_rescueauth_launcher_monochrome.xml': vector(launcher=True, mono=True),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail when checked-in Android vectors differ from the SVG master.')
    args = parser.parse_args()
    mismatches = []
    for name, text in outputs().items():
        file = DEST / name
        if args.check:
            if not file.exists() or file.read_text() != text:
                mismatches.append(name)
        else:
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text)
    if mismatches:
        print('Brand vector drift: ' + ', '.join(mismatches), file=sys.stderr)
        return 1
    print('SVG and Android brand vectors match.' if args.check else 'Generated four brand vectors from the SVG master.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
