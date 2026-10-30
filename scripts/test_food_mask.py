import base64
import io
import unittest
import numpy as np
from PIL import Image
from ar_volume import estimate
from food_mask import encode_mask, decode_mask, sample_mask
from test_ar_volume import fixture


class FoodMaskTest(unittest.TestCase):
    def test_binary_roundtrip_preserves_holes(self):
        mask = np.ones((8, 12), dtype=bool)
        mask[2:5, 3:6] = False
        spec = encode_mask(mask, "test")
        np.testing.assert_array_equal(mask, decode_mask(spec, [12, 8]))

    def test_dimensions_and_wrong_encoding_rejected(self):
        spec = encode_mask(np.ones((8, 12)), "test")
        with self.assertRaises(ValueError):
            decode_mask(spec, [8, 12])
        spec["encoding"] = "jpeg"
        with self.assertRaises(ValueError):
            decode_mask(spec, [12, 8])

    def test_invalid_base64_and_nonbinary_pixels_rejected(self):
        spec = encode_mask(np.ones((8, 12)), "test")
        spec["data"] = "not/base64!"
        with self.assertRaises(ValueError):
            decode_mask(spec, [12, 8])
        buffer = io.BytesIO()
        Image.fromarray(np.full((8, 12), 120, np.uint8)).save(buffer, format="PNG")
        spec["data"] = base64.b64encode(buffer.getvalue()).decode()
        with self.assertRaisesRegex(ValueError, "0 또는 255"):
            decode_mask(spec, [12, 8])

    def test_nearest_mapping_rejects_outside_image(self):
        mask = np.array([[True, False], [False, True]])
        uv = np.array([[[.25, .25], [.75, .25], [1., .5], [-.1, .2], [np.nan, .2]]])
        np.testing.assert_array_equal(sample_mask(mask, uv), [[True, False, False, False, False]])

    def test_mask_and_polygon_give_same_volume(self):
        meta, depth, confidence, annotation = fixture()
        baseline = estimate(meta, depth, confidence, annotation)
        mask = np.zeros((80, 80), dtype=bool)
        mask[30:50, 30:50] = True
        annotation["foodMask"] = encode_mask(mask, "synthetic")
        annotation["foodMask"]["reviewed"] = True
        annotation["foodPolygons"] = []
        result = estimate(meta, depth, confidence, annotation)
        self.assertEqual(result["volumeMl"], baseline["volumeMl"])
        self.assertEqual(result["method"], "segmented_mask_depth_mesh_v1")

    def test_unreviewed_mask_is_held(self):
        meta, depth, confidence, annotation = fixture()
        mask = np.zeros((80, 80), dtype=bool)
        mask[30:50, 30:50] = True
        annotation["foodMask"] = encode_mask(mask, "synthetic")
        result = estimate(meta, depth, confidence, annotation)
        self.assertIsNone(result["volumeMl"])
        self.assertIn("생성된 음식 경계 검토 필요", result["holdReasons"])
        self.assertGreater(result["observedVolumeMl"], 0)

    def test_mask_support_overlap_is_rejected(self):
        meta, depth, confidence, annotation = fixture()
        annotation["foodMask"] = encode_mask(np.ones((80, 80), dtype=bool), "test")
        with self.assertRaisesRegex(ValueError, "겹칩니다"):
            estimate(meta, depth, confidence, annotation)


if __name__ == "__main__":
    unittest.main()
