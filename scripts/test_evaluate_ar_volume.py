import copy
import unittest
from evaluate_ar_volume import evaluate


def rows():
    return [
        {"portion_id": str(i), "food": "합성 음식", "split": "calibration" if i < 3 else "evaluation",
         "scale_grams": str((i+1)*80),
         "result": {"frameFingerprint": f"{i:064x}", "status": "EXPERIMENT_ESTIMATE", "volumeMl": (i+1)*100}}
        for i in range(5)
    ]


class EvaluationTest(unittest.TestCase):
    def test_density_and_held_out_errors(self):
        result = evaluate(rows())["foods"]["합성 음식"]
        self.assertAlmostEqual(result["densityGPerMl"], .8)
        self.assertAlmostEqual(result["meanAbsoluteErrorGrams"], 0)
        self.assertEqual(result["portionEstimateRate"], 1)

    def test_same_portion_cannot_cross_splits(self):
        data = rows()
        data[-1]["portion_id"] = "0"
        with self.assertRaises(ValueError):
            evaluate(data)

    def test_repeated_frames_cannot_inflate_sample_count(self):
        data = rows()
        data.append(copy.deepcopy(data[0]))
        with self.assertRaises(ValueError):
            evaluate(data)

    def test_failed_evaluation_kept_in_coverage_denominator(self):
        data = rows()
        data[-1]["result"].update(status="REVIEW_REQUIRED", volumeMl=None)
        result = evaluate(data)["foods"]["합성 음식"]
        self.assertEqual(result["portionEstimateRate"], .5)
        self.assertEqual(result["frameVolumeAvailabilityRate"], .5)

    def test_eval_scale_does_not_change_calibration(self):
        data = rows()
        data[-1]["scale_grams"] = "999"
        self.assertAlmostEqual(evaluate(data)["foods"]["합성 음식"]["densityGPerMl"], .8)

    def test_insufficient_calibration_leaves_weight_unknown(self):
        result = evaluate(rows()[1:])["foods"]["합성 음식"]
        self.assertIsNone(result["densityGPerMl"])
        self.assertIsNone(result["medianRelativeErrorPercent"])


if __name__ == "__main__":
    unittest.main()
