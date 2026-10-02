from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

try:
    from PIL import Image
except ImportError:  # pragma: no cover - the comparison script itself needs Pillow
    Image = None

SCRIPT = Path(__file__).with_name("design-compare.py")


@unittest.skipIf(Image is None, "Pillow is not installed")
class DesignCompareTest(unittest.TestCase):
    def run_script(self, app_size, figma_size):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        Image.new("RGB", app_size, (0, 0, 255)).save(root / "app.png")
        Image.new("RGB", figma_size, (255, 0, 0)).save(root / "figma.png")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(root / "app.png"), str(root / "figma.png"), str(root / "out/welcome")],
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return Image.open(root / "out/welcome-side-by-side.png"), Image.open(root / "out/welcome-overlay.png")

    def test_side_by_side_puts_figma_left_and_app_right(self):
        pair, _ = self.run_script((40, 60), (40, 60))
        self.assertEqual((88, 80), pair.size)
        self.assertEqual((255, 0, 0), pair.getpixel((20, 50)))
        self.assertEqual((0, 0, 255), pair.getpixel((68, 50)))

    def test_overlay_blends_both_halves(self):
        _, blend = self.run_script((40, 60), (40, 60))
        self.assertEqual((40, 60), blend.size)
        red, green, blue = blend.getpixel((10, 10))
        self.assertAlmostEqual(127, red, delta=1)
        self.assertEqual(0, green)
        self.assertAlmostEqual(127, blue, delta=1)

    def test_figma_export_is_scaled_to_the_capture(self):
        pair, blend = self.run_script((40, 60), (80, 120))
        self.assertEqual((40, 60), blend.size)
        self.assertEqual((88, 80), pair.size)

    def test_wrong_arguments_print_usage(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("Usage", result.stderr)


if __name__ == "__main__":
    unittest.main()
