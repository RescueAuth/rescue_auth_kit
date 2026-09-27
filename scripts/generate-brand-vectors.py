#!/usr/bin/env python3
"""Build Android vectors from the path-and-gradient Shiyifang SVG master."""
from pathlib import Path
import argparse
import math
import re
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'design/brand/shiyifang-logo.svg'
DEST = ROOT / 'app/src/main/res/drawable'
ANDROID = 'http://schemas.android.com/apk/res/android'
SVG = 'http://www.w3.org/2000/svg'


def color_with_alpha(color, opacity='1'):
    if not re.fullmatch(r'#[0-9a-fA-F]{6}', color or ''):
        raise ValueError('Brand colors must be explicit six-digit RGB values.')
    alpha = float(opacity)
    if not 0 <= alpha <= 1:
        raise ValueError('Opacity must be between zero and one.')
    return f'#{round(alpha * 255):02X}' + color[1:]


def outputs():
    source = ET.parse(SOURCE).getroot()
    if source.get('viewBox') != '0 0 128 128':
        raise ValueError('The brand master must keep a 128 by 128 viewBox.')
    allowed_elements = {'svg', 'title', 'desc', 'defs', 'linearGradient', 'radialGradient', 'stop', 'path'}
    for element in source.iter():
        if element.tag.removeprefix('{' + SVG + '}') not in allowed_elements:
            raise ValueError('Unsupported SVG element: ' + element.tag)
        if any(key in element.attrib for key in ('transform', 'gradientTransform', 'style', 'filter', 'mask', 'clip-path')):
            raise ValueError('Unsupported SVG transform, style or effect.')
    launcher_scale = float(source.get('data-launcher-scale', '0.6'))
    if not 0 < launcher_scale <= 0.7:
        raise ValueError('Invalid adaptive launcher scale.')
    launcher_offset = 54 - 64 * launcher_scale
    paths = source.findall(f'{{{SVG}}}path')
    if not paths or any(path.get('d') is None or path.get('fill') is None for path in paths):
        raise ValueError('Only explicitly filled SVG paths are supported.')
    if any(set(path.attrib) - {'id', 'd', 'fill', 'fill-rule', 'fill-opacity', 'stroke', 'stroke-width',
                               'stroke-linecap', 'stroke-linejoin'} for path in paths):
        raise ValueError('Unsupported path attributes would be lost during conversion.')
    gradients = {g.get('id'): g for g in source.findall(f'{{{SVG}}}defs/*')}
    mono_id = source.get('data-monochrome')
    mono_paths = [p for p in paths if p.get('id') == mono_id]
    if len(mono_paths) != 1:
        raise ValueError('Exactly one monochrome silhouette is required.')

    def gradient_xml(gradient, color_attribute='fillColor'):
        if gradient.get('gradientUnits') != 'userSpaceOnUse':
            raise ValueError('Use explicit userSpaceOnUse gradient coordinates.')
        radial = gradient.tag == f'{{{SVG}}}radialGradient'
        coordinates = {'cx': 'centerX', 'cy': 'centerY', 'r': 'gradientRadius'} if radial else {
            'x1': 'startX', 'y1': 'startY', 'x2': 'endX', 'y2': 'endY'}
        if set(gradient.attrib) - {'id', 'gradientUnits', *coordinates}:
            raise ValueError('Unsupported gradient attributes.')
        attributes = [f'android:type="{ "radial" if radial else "linear" }"']
        for svg_name, android_name in coordinates.items():
            value = float(gradient.attrib[svg_name])
            if svg_name == 'r' and value <= 0:
                raise ValueError('Radial gradients require a positive radius.')
            attributes.append(f'android:{android_name}="{value:g}"')
        xml = [f'            <aapt:attr name="android:{color_attribute}">',
               '                <gradient ' + ' '.join(attributes) + '>']
        stops = list(gradient)
        if len(stops) < 2:
            raise ValueError('A gradient needs at least two stops.')
        previous = -1.0
        for stop in stops:
            if stop.tag != f'{{{SVG}}}stop' or set(stop.attrib) - {'offset', 'stop-color', 'stop-opacity'}:
                raise ValueError('Unsupported gradient stop.')
            raw = stop.attrib['offset']
            offset = float(raw[:-1]) / 100 if raw.endswith('%') else float(raw)
            if not previous <= offset <= 1 or offset < 0:
                raise ValueError('Gradient offsets must be ordered between zero and one.')
            previous = offset
            color = color_with_alpha(stop.get('stop-color'), stop.get('stop-opacity', '1'))
            xml.append(f'                    <item android:offset="{offset:g}" android:color="{color}"/>')
        return xml + ['                </gradient>', '            </aapt:attr>']

    def vector(launcher=False, mono=False):
        size = 108 if launcher else 128
        xml = ['<?xml version="1.0" encoding="utf-8"?>',
               '<!-- Generated from design/brand/shiyifang-logo.svg. Run scripts/generate-brand-vectors.py. -->',
               f'<vector xmlns:android="{ANDROID}" xmlns:aapt="http://schemas.android.com/aapt" android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">']
        if launcher:
            xml.append(f'    <group android:scaleX="{launcher_scale:g}" android:scaleY="{launcher_scale:g}" android:translateX="{launcher_offset:g}" android:translateY="{launcher_offset:g}">')
        for path in mono_paths if mono else paths:
            attributes = [f'android:name={quoteattr(path.attrib["id"])}',
                          f'android:pathData={quoteattr(path.attrib["d"])}']
            rule = path.get('fill-rule', 'nonzero')
            if rule not in ('nonzero', 'evenodd'):
                raise ValueError('Unsupported fill rule.')
            attributes.append(f'android:fillType="{ "evenOdd" if rule == "evenodd" else "nonZero" }"')
            paints = []
            def paint(value, attribute, opacity='1'):
                reference = re.fullmatch(r'url\(#([\w-]+)\)', value)
                if reference:
                    gradient = gradients.get(reference.group(1))
                    if gradient is None:
                        raise ValueError('Missing gradient: ' + value)
                    paints.extend(gradient_xml(gradient, attribute))
                else:
                    color = '#00000000' if value == 'none' else color_with_alpha(value, opacity)
                    if mono and attribute == 'fillColor':
                        color = '#FFFFFF'
                    attributes.append(f'android:{attribute}="{color}"')
            paint('#FFFFFF' if mono else path.attrib['fill'], 'fillColor', path.get('fill-opacity', '1'))
            if not mono and path.get('stroke', 'none') != 'none':
                width = float(path.get('stroke-width', '1'))
                cap = path.get('stroke-linecap', 'butt')
                join = path.get('stroke-linejoin', 'miter')
                if not math.isfinite(width) or width <= 0 or cap not in ('butt', 'round', 'square') or join not in ('miter', 'round', 'bevel'):
                    raise ValueError('Unsupported stroke geometry.')
                attributes.extend([f'android:strokeWidth="{width:g}"', f'android:strokeLineCap="{cap}"', f'android:strokeLineJoin="{join}"'])
                paint(path.attrib['stroke'], 'strokeColor')
            if paints:
                xml.append('        <path ' + ' '.join(attributes) + '>')
                xml.extend(paints)
                xml.append('        </path>')
            else:
                xml.append('        <path ' + ' '.join(attributes) + '/>')
        if launcher:
            xml.append('    </group>')
        return '\n'.join(xml + ['</vector>\n'])

    return {
        'ic_rescueauth_mark.xml': vector(),
        'ic_rescueauth_mark_mono.xml': vector(mono=True),
        'ic_rescueauth_launcher_foreground.xml': vector(launcher=True),
        'ic_rescueauth_launcher_monochrome.xml': vector(launcher=True, mono=True),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail when Android vectors differ from the SVG master.')
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
    print('SVG and Android brand vectors match.' if args.check else 'Generated four vectors from the Shiyifang SVG master.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
