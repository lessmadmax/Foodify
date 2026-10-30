import unittest
from run_gemma4 import parse_foods


class FoodResponseTest(unittest.TestCase):
    def test_location_coordinates(self):
        text = '{"foods":[{"name":"밥","bbox":[0.2,0.3,0.7,0.8]}]}'
        self.assertEqual(parse_foods(text, locate=True)[0]["bbox"], [.2, .3, .7, .8])
        for box in ("[0.8,0.2,0.1,0.5]", "[0,0,2,1]", "[true,0,1,1]", "[0,0,NaN,1]"):
            with self.assertRaises(ValueError):
                parse_foods('{"foods":[{"name":"밥","bbox":'+box+'}]}', locate=True)

    def test_korean_names_and_whitespace(self):
        self.assertEqual(parse_foods('{"foods":[{"name":" 밥 "},{"name":"김치"}]}'),
                         [{"name": "밥"}, {"name": "김치"}])

    def test_fenced_json(self):
        fence = chr(96)*3
        self.assertEqual(parse_foods("\n".join([fence+"json", '{"foods":[]}', fence])), [])

    def test_plain_prose_rejected(self):
        with self.assertRaises(ValueError):
            parse_foods("밥과 김치로 보입니다.")

    def test_extra_nutrition_fields_rejected(self):
        with self.assertRaises(ValueError):
            parse_foods('{"foods":[{"name":"밥","calories":200}]}')

    def test_empty_name_and_wrong_container_rejected(self):
        for text in ('{"foods":[{"name":""}]}', '{"foods":null}', '[]', '{"foods":[null]}'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_foods(text)

    def test_repeated_food_instances_preserved(self):
        self.assertEqual(len(parse_foods('{"foods":[{"name":"밥"},{"name":"밥"}]}')), 2)


if __name__ == "__main__":
    unittest.main()
