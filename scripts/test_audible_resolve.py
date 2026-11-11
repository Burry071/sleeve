#!/usr/bin/env python3
"""Unit tests for ASIN extraction in app/src/main/python/sleeve_audible.py.

A pasted Audible product URL must resolve by ASIN, never by sending the whole
URL through the keyword search. Numeric ASINs are real: The Fellowship of the
Ring is 1705047572 and Atomic Habits is 1524779261.
"""

from __future__ import annotations

import json
import os
import sys
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_ROOT, "app", "src", "main", "python"))

import sleeve_audible


class FakeApi:
    def __init__(self):
        self.calls = []

    def __call__(self, url):
        self.calls.append(url)
        return {
            "product": {
                "title": "Book",
                "authors": [{"name": "Author"}],
                "narrators": [{"name": "Narrator"}],
                "runtime_length_min": 600,
            }
        }


class AsinExtractionTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeApi()
        self.original = sleeve_audible._api
        sleeve_audible._api = self.api

    def tearDown(self):
        sleeve_audible._api = self.original

    def assert_resolved(self, url, asin):
        result = json.loads(sleeve_audible.resolve(url))
        self.assertTrue(result["ok"], result)
        self.assertEqual(self.api.calls, [
            "https://api.audible.com/1.0/catalog/products/"
            + asin
            + "?response_groups=product_attrs,contributors,product_desc"
        ])

    def test_letter_asin_product_url(self):
        self.assert_resolved("https://www.audible.com/pd/B002V1OF70", "B002V1OF70")

    def test_numeric_asin_product_url(self):
        self.assert_resolved("https://www.audible.com/pd/1524779261", "1524779261")

    def test_slugged_product_url(self):
        self.assert_resolved(
            "https://www.audible.com/pd/The-Fellowship-of-the-Ring-Audiobook/1705047572",
            "1705047572",
        )

    def test_product_url_with_query(self):
        self.assert_resolved(
            "https://www.audible.com/pd/1984-Audiobook/B0D63FJ2ZK?ref=a_search",
            "B0D63FJ2ZK",
        )

    def test_bare_asin(self):
        self.assert_resolved("B08G9PRS1K", "B08G9PRS1K")

    def test_keyword_url_without_asin_uses_keywords(self):
        def search_api(url):
            self.api.calls.append(url)
            return {
                "products": [
                    {
                        "title": "Dune",
                        "authors": [{"name": "Frank Herbert"}],
                        "narrators": [{"name": "Scott Brick"}],
                        "runtime_length_min": 1262,
                    }
                ]
            }

        sleeve_audible._api = search_api
        result = json.loads(sleeve_audible.resolve("", "Dune"))
        self.assertTrue(result["ok"], result)
        self.assertIn("keywords=Dune", self.api.calls[0])


if __name__ == "__main__":
    unittest.main()
