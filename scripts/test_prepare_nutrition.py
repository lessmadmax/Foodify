import unittest
from prepare_nutrition import amount, number

class ConversionTest(unittest.TestCase):
    def test_units(self):
        self.assertEqual(amount('100g'), (100.0,'g'))
        self.assertEqual(amount('100ml'), (100.0,'ml'))
        self.assertEqual(amount('1정'), (None,None))
    def test_missing_is_distinct_from_zero(self):
        self.assertIsNone(number(''))
        self.assertEqual(number('0'),0)
    def test_invalid_values(self):
        for value in ['-1','NaN','Infinity','미상','0.0001']:
            with self.assertRaises(ValueError): number(value)

if __name__=='__main__': unittest.main()
