"""Check that the editable gradient master survives Android conversion."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('brand_vectors', HERE / 'generate-brand-vectors.py')
brand = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brand)
NS = {'android': brand.ANDROID, 'aapt': 'http://schemas.android.com/aapt'}
A = '{' + brand.ANDROID + '}'


class BrandVectorTest(unittest.TestCase):
    def test_gold_rims_follow_only_the_two_gold_surfaces(self):
        root = ET.parse(brand.SOURCE).getroot()
        paths = {p.get('id'): p for p in root.findall('{' + brand.SVG + '}path')}
        stroked = {name for name, p in paths.items() if p.get('stroke')}
        expected = {f'gold-{side}-rim-{layer}' for side in ('left', 'right') for layer in ('depth', 'shine')}
        self.assertEqual(stroked, expected)
        for name in expected:
            surface = '-'.join(name.split('-')[:2])
            self.assertEqual(paths[name].get('d'), paths[surface].get('d'))
            self.assertEqual(paths[name].get('fill'), 'none')
            self.assertLessEqual(float(paths[name].get('stroke-width')), .65)

    def test_gold_strokes_keep_gradient_width_and_round_join_in_android(self):
        root = ET.fromstring(brand.outputs()['ic_rescueauth_mark.xml'])
        strokes = [p for p in root.findall('.//path') if p.get(A + 'strokeWidth')]
        self.assertEqual(len(strokes), 4)
        for path in strokes:
            self.assertTrue(path.get(A + 'name').startswith(('gold-left-rim-', 'gold-right-rim-')))
            self.assertEqual(path.get(A + 'fillColor'), '#00000000')
            self.assertEqual(path.get(A + 'strokeLineJoin'), 'round')
            attributes = path.findall('{http://schemas.android.com/aapt}attr')
            self.assertEqual([a.get('name') for a in attributes], ['android:strokeColor'])
            self.assertGreater(len(attributes[0].find('gradient').findall('item')), 1)

    def test_master_ids_are_unique_and_all_surfaces_reach_android(self):
        source = ET.parse(brand.SOURCE).getroot()
        identifiers = [element.get('id') for element in source.iter() if element.get('id')]
        self.assertEqual(len(identifiers), len(set(identifiers)))
        expected = {path.get('id') for path in source.findall('{' + brand.SVG + '}path')}
        vector = ET.fromstring(brand.outputs()['ic_rescueauth_mark.xml'])
        self.assertEqual({path.get(A + 'name') for path in vector.findall('.//path')}, expected)

    def test_colored_vectors_keep_both_gradient_types_and_stop_alpha(self):
        for name in ('ic_rescueauth_mark.xml', 'ic_rescueauth_launcher_foreground.xml'):
            root = ET.fromstring(brand.outputs()[name])
            gradients = root.findall('.//gradient')
            self.assertEqual({g.get(A + 'type') for g in gradients}, {'linear', 'radial'})
            self.assertTrue(any(stop.get(A + 'color', '').startswith('#00')
                                for gradient in gradients for stop in gradient))
            self.assertTrue(all(len(gradient.findall('item')) >= 2 for gradient in gradients))

    def test_monochrome_keeps_the_key_aperture_without_shading_layers(self):
        for name in ('ic_rescueauth_mark_mono.xml', 'ic_rescueauth_launcher_monochrome.xml'):
            root = ET.fromstring(brand.outputs()[name])
            paths = root.findall('.//path')
            self.assertEqual(len(paths), 1)
            self.assertEqual(paths[0].get(A + 'fillType'), 'evenOdd')
            self.assertEqual(paths[0].get(A + 'fillColor'), '#FFFFFF')
            self.assertEqual(paths[0].get(A + 'name'), 'silhouette')
            self.assertGreaterEqual(paths[0].get(A + 'pathData').count('M'), 2)
            self.assertEqual(root.findall('.//gradient'), [])

    def test_launcher_is_centered_and_fifteen_percent_larger(self):
        root = ET.fromstring(brand.outputs()['ic_rescueauth_launcher_foreground.xml'])
        group = root.find('group')
        scale = float(group.get(A + 'scaleX'))
        offset = float(group.get(A + 'translateX'))
        self.assertAlmostEqual(offset + 64 * scale, 54)
        self.assertEqual(group.get(A + 'scaleX'), group.get(A + 'scaleY'))
        self.assertEqual(group.get(A + 'translateX'), group.get(A + 'translateY'))
        self.assertAlmostEqual(scale, 0.6 * 1.15)


if __name__ == '__main__':
    unittest.main()
