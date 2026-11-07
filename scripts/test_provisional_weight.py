import unittest
import numpy as np
from fit_provisional_weight import fit, provisional_plane

class ProvisionalWeightTest(unittest.TestCase):
    def test_recovers_known_coefficient_and_is_explicitly_unvalidated(self):
        result=fit([{'foodName':'synthetic','scaleGrams':8.,'observedPartialVolumeMl':10.}, {'foodName':'synthetic','scaleGrams':16.,'observedPartialVolumeMl':20.}])
        self.assertAlmostEqual(result['coefficientGramsPerObservedMl'],.8)
        self.assertEqual(result['interceptGrams'],0)
        self.assertAlmostEqual(result['trainingMaeGrams'],0)
        self.assertFalse(result['serviceApplicable'])
        self.assertEqual(result['independentValidationSamples'],0)

    def test_zero_and_nonfinite_features_are_rejected(self):
        for v in [0., -1., float('nan'), float('inf')]:
            with self.assertRaises(ValueError):
                fit([{'foodName':'synthetic','scaleGrams':8.,'observedPartialVolumeMl':v}])

    def test_plane_hypothesis_preserves_geometry_without_scale_labels(self):
        points=np.array([[x*10.,y*10.,500.]for x in range(10)for y in range(10)])
        normal,offset,ratio,rmse=provisional_plane(points)
        self.assertTrue(np.allclose(points@normal+offset,0,atol=1e-7))
        self.assertAlmostEqual(offset,500.)
        self.assertAlmostEqual(ratio,1.)
        self.assertLess(rmse,1e-7)

if __name__=='__main__':unittest.main()
