import copy
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

from ar_volume import estimate, fit_plane, make_annotation_page, points_from_depth, read_frame


def fixture():
    meta = {
        "schemaVersion": 1, "depthWidth": 80, "depthHeight": 80,
        "depthUnit": "millimetres_camera_z", "trackingState": "TRACKING",
        "frameTimestampNs": "1000000000", "depthTimestampNs": "1000000000",
        "cameraTimestampNs": "1000000000", "confidenceTimestampNs": "1000000000",
        "depthNormalizedCorners": [0, 0, 1, 0, 0, 1, 1, 1],
        "correspondingCpuImageNormalizedCorners": [0, 0, 1, 0, 0, 1, 1, 1],
        "cpuIntrinsics": {"imageDimensions": [80, 80], "focalLength": [100, 100], "principalPoint": [40, 40]},
    }
    depth = np.full((80, 80), 500.)
    depth[30:50, 30:50] = 450
    confidence = np.full((80, 80), 255, np.uint8)
    annotation = {
        "schemaVersion": 1, "coordinateSpace": "cpu_image_normalized",
        "foodPolygons": [[[.375, .375], [.625, .375], [.625, .625], [.375, .625]]],
        "basePolygons": [[[.1, .1], [.3, .1], [.3, .9], [.1, .9]],
                         [[.7, .1], [.9, .1], [.9, .9], [.7, .9]]],
    }
    return meta, depth, confidence, annotation


class VolumeTest(unittest.TestCase):
    def test_flat_cuboid_observed_footprint_and_density(self):
        meta, depth, confidence, annotation = fixture()
        expected_ml = (19 * 4.5) ** 2 * 50 / 1000
        annotation.update(densityGPerMl=.8, densitySource="synthetic calibration", scaleGrams=300)
        result = estimate(meta, depth, confidence, annotation)
        self.assertEqual(result["status"], "EXPERIMENT_ESTIMATE")
        self.assertAlmostEqual(result["volumeMl"], expected_ml, places=3)
        self.assertAlmostEqual(result["estimatedGrams"], expected_ml * .8)
        self.assertFalse(result["volumeValidated"])
        self.assertLess(result["basePlaneRmseMm"], 1e-8)

    def test_no_density_no_weight(self):
        result = estimate(*fixture())
        self.assertIsNotNone(result["volumeMl"])
        self.assertIsNone(result["estimatedGrams"])

    def test_missing_base_depth_produces_failed_evaluation_record(self):
        meta, depth, confidence, annotation = fixture()
        confidence[:] = 0
        result = estimate(meta, depth, confidence, annotation)
        self.assertEqual(result["status"], "REVIEW_REQUIRED")
        self.assertIsNone(result["volumeMl"])
        self.assertTrue(result["holdReasons"])

    def test_sparse_depth_is_held_not_filled(self):
        meta, depth, confidence, annotation = fixture()
        confidence[30:50:2, 30:50] = 0
        result = estimate(meta, depth, confidence, annotation)
        self.assertEqual(result["status"], "REVIEW_REQUIRED")
        self.assertIsNone(result["volumeMl"])
        self.assertLess(result["meshCoverage"], .8)

    def test_stale_and_mismatched_timestamps(self):
        meta, depth, confidence, annotation = fixture()
        meta["depthTimestampNs"] = "800000000"
        result = estimate(meta, depth, confidence, annotation)
        self.assertIsNone(result["volumeMl"])
        self.assertTrue(any("동일 프레임 획득 근거" in reason for reason in result["holdReasons"]))

    def test_known_same_frame_pair_accepts_reprojection_timestamp(self):
        meta, depth, confidence, annotation = fixture()
        meta["pairAcquisition"] = "same_arcore_frame"
        meta["depthTimestampNs"] = "900000000"
        result = estimate(meta, depth, confidence, annotation)
        self.assertIsNotNone(result["volumeMl"])

    def test_known_pair_still_rejects_stale_confidence(self):
        meta, depth, confidence, annotation = fixture()
        meta["pairAcquisition"] = "same_arcore_frame"
        meta["confidenceTimestampNs"] = "700000000"
        result = estimate(meta, depth, confidence, annotation)
        self.assertIsNone(result["volumeMl"])
        self.assertTrue(any("프레임 대응" in reason for reason in result["holdReasons"]))

    def test_below_support_surface_is_held(self):
        meta, depth, confidence, annotation = fixture()
        depth[30:50, 30:50] = 520
        self.assertIsNone(estimate(meta, depth, confidence, annotation)["volumeMl"])

    def test_whole_plate_excludes_food(self):
        meta, depth, confidence, annotation = fixture()
        annotation["basePolygons"] = [[[.1,.1],[.9,.1],[.9,.9],[.1,.9]]]
        result = estimate(meta, depth, confidence, annotation)
        self.assertEqual(result["status"], "EXPERIMENT_ESTIMATE")
        self.assertAlmostEqual(result["volumeMl"], 365.5125, places=3)
        self.assertEqual(result["excludedFoodPixelCount"], 400)
        self.assertEqual(result["baseCandidatePixelCount"], 3696)
        self.assertAlmostEqual(result["basePlaneOffsetMm"], 500)

    def test_plate_only_covering_food_is_held(self):
        meta, depth, confidence, annotation = fixture()
        annotation["basePolygons"] = copy.deepcopy(annotation["foodPolygons"])
        result = estimate(meta, depth, confidence, annotation)
        self.assertIsNone(result["volumeMl"])
        self.assertEqual(result["baseCandidatePixelCount"], 0)
        self.assertTrue(any("음식을 제외한 접시 표면" in x for x in result["holdReasons"]))

    def test_cropped_and_mirrored_coordinate_mapping(self):
        meta, depth, _, _ = fixture()
        meta["correspondingCpuImageNormalizedCorners"] = [1, .1, 0, .1, 1, .9, 0, .9]
        points, uv = points_from_depth(meta, depth)
        self.assertAlmostEqual(uv[0, 0, 0], 1 - .5/80)
        self.assertAlmostEqual(uv[0, 0, 1], .1 + .8*.5/80)
        self.assertAlmostEqual(points[0, 0, 0], ((1-.5/80)*80-40)/100*500)

    def test_collinear_support_rejected(self):
        points = np.c_[np.arange(30), np.zeros(30), np.full(30, 500)]
        with self.assertRaises(ValueError):
            fit_plane(points)

    def test_plane_robust_to_small_outlier_fraction(self):
        x, y = np.meshgrid(np.arange(20)*5., np.arange(20)*5.)
        points = np.c_[x.ravel(), y.ravel(), np.full(400, 500.)]
        points[:20, 2] += 50
        normal, offset, rmse, ratio = fit_plane(points)
        self.assertAlmostEqual(offset, 500)
        self.assertAlmostEqual(normal[2], -1)
        self.assertAlmostEqual(rmse, 0)
        self.assertAlmostEqual(ratio, .95)

    def test_steep_angle_held(self):
        meta, depth, confidence, annotation = fixture()
        y, x = np.mgrid[:80, :80]
        denominator = 1 + (x + .5 - 40)/100
        depth[:] = 500/denominator
        depth[30:50, 30:50] = (500-50*np.sqrt(2))/denominator[30:50, 30:50]
        result = estimate(meta, depth, confidence, annotation)
        self.assertAlmostEqual(result["cameraTiltDegrees"], 45)
        self.assertIsNone(result["volumeMl"])

    def test_density_requires_provenance(self):
        meta, depth, confidence, annotation = fixture()
        annotation["densityGPerMl"] = 1
        with self.assertRaisesRegex(ValueError, "환산 근거"):
            estimate(meta, depth, confidence, annotation)

    def test_saved_frame_and_offline_annotation_export(self):
        meta, depth, confidence, _ = fixture()
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder/"metadata.json").write_text(json.dumps(meta), encoding="utf-8")
            (folder/"depth.u16le").write_bytes(depth.astype("<u2").tobytes())
            (folder/"confidence.u8").write_bytes(confidence.tobytes())
            (folder/"camera-y.u8").write_bytes(bytes([128])*6400)
            for name in ("camera-u.u8", "camera-v.u8"):
                (folder/name).write_bytes(bytes([128])*1600)
            read_meta, read_depth, _ = read_frame(folder)
            self.assertEqual(read_meta, meta)
            np.testing.assert_equal(read_depth, depth)
            make_annotation_page(folder, folder/"annotation.html")
            html = (folder/"annotation.html").read_text(encoding="utf-8")
            self.assertIn("data:image/jpeg;base64,", html)
            self.assertNotIn("__FRAME_FINGERPRINT__", html)
            (folder/"depth.u16le").write_bytes(b"bad")
            with self.assertRaises(ValueError):
                read_frame(folder)


if __name__ == "__main__":
    unittest.main()
