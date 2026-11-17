"""Offline resource contract, NOT an Android measure/layout test.

The actual framework measurement assertions are in MenuTileLayoutAssertions.java.
Run this file directly from any directory; it needs only Python's stdlib.
"""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

SRC = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
LAYOUT = SRC / "mobile/res/layout/material_menu.xml"


def menu_styles():
    styles = {}
    for path in (SRC / "mobile/res/values").glob("*.xml"):
        for style in ET.parse(path).getroot().findall("style"):
            styles[style.attrib["name"]] = style
    return styles


def items(style_name, styles):
    name = style_name.removeprefix("@style/")
    style = styles.get(name)
    if style is None:
        return {}
    result = items(style.attrib.get("parent", ""), styles)
    result.update({item.attrib["name"]: item.text for item in style.findall("item")})
    return result


class MenuTileContractTest(unittest.TestCase):
    def test_one_and_two_line_tiles_use_same_row_height(self):
        root = ET.parse(LAYOUT).getroot()
        styles = menu_styles()
        row = next(row for row in root.iter("LinearLayout")
                   if any(child.attrib.get(ANDROID + "id") == "@+id/jm_sensor"
                          for child in row))
        # Natural sizes are synthetic; this checks the resource contract used
        # by LinearLayout's forceUniformHeight pass, not Android rendering.
        natural = [64, 88]
        resolved = [items(child.attrib["style"], styles) for child in row]
        measured = [max(natural) if style.get("android:layout_height") == "match_parent"
                    else height for style, height in zip(resolved, natural)]
        self.assertEqual(measured[0], measured[1],
                         "wrap_content leaves a one-line tile shorter than its wrapped sibling")

    def test_all_menu_tile_rows_reserve_lines_without_a_height_or_line_cap(self):
        root = ET.parse(LAYOUT).getroot()
        styles = menu_styles()
        tiles = [node for node in root.iter()
                 if node.attrib.get("style") in {
                     "@style/Juggluco.M3.MenuTile", "@style/Juggluco.M3.MenuTimeButton"}]
        self.assertEqual(len(tiles), 20)
        for tile in tiles:
            style = items(tile.attrib["style"], styles)
            with self.subTest(tile=tile.attrib[ANDROID + "id"]):
                self.assertEqual(style["android:layout_height"], "match_parent")
                self.assertEqual(style["android:minLines"], "2")
                self.assertEqual(style["android:maxLines"], "2147483647")
                self.assertEqual(style["android:ellipsize"], "@null")
                self.assertEqual(style["android:layout_width"], "0dp")
                self.assertEqual(style["android:layout_weight"], "1")
                self.assertIn(ANDROID + "text", tile.attrib)
                self.assertIn("{http://schemas.android.com/apk/res-auto}icon", tile.attrib)
        parents = {child: parent for parent in root.iter() for child in parent}
        for tile in tiles:
            parent = parents[tile]
            self.assertEqual(parent.tag, "LinearLayout")
            self.assertEqual(parent.attrib[ANDROID + "orientation"], "horizontal")
            self.assertEqual(parent.attrib[ANDROID + "layout_height"], "wrap_content")
            self.assertTrue(all(child in tiles for child in parent))

    def test_shared_styles_and_appearance_are_not_restyled(self):
        styles = menu_styles()
        shared = items("Juggluco.M3.Tile", styles)
        self.assertEqual(shared["android:layout_height"], "wrap_content")
        self.assertEqual(shared["android:maxLines"], "2")
        self.assertEqual(shared["android:ellipsize"], "end")
        root = ET.parse(LAYOUT).getroot()
        appearance = next(node for node in root.iter()
                          if node.attrib.get(ANDROID + "id") == "@+id/jm_appearance")
        self.assertEqual([node.attrib[ANDROID + "id"] for node in appearance], [
            "@+id/jm_appearance_system", "@+id/jm_appearance_light", "@+id/jm_appearance_dark"])
        self.assertTrue(all(node.attrib["style"] == "?attr/materialButtonOutlinedStyle"
                            for node in appearance))


if __name__ == "__main__":
    unittest.main()
