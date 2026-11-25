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

    def test_track_carries_no_invented_album(self):
        # The catalogue has no album field; the old "Audible" placeholder would
        # be written into every exported .m4b as if it were real metadata.
        result = json.loads(sleeve_audible.resolve("B08G9PRS1K"))
        self.assertTrue(result["ok"], result)
        track = result["tracks"][0]
        self.assertNotIn("album", track)
        self.assertEqual(track["artist"], "Author (narrated by Narrator)")
        self.assertEqual(track["authors"], "Author")

    def test_queries_are_unchanged_by_the_tag_work(self):
        sleeve_audible.resolve("B08G9PRS1K")
        self.assertEqual(
            self.api.calls,
            [
                "https://api.audible.com/1.0/catalog/products/B08G9PRS1K"
                "?response_groups=product_attrs,contributors,product_desc"
            ],
        )

    def test_catalogue_language_reaches_the_track_as_one_token(self):
        # The gate maps names and codes alike, so the region and casing must
        # not hide the language the book was actually narrated in.
        sleeve_audible._api = lambda url: {
            "product": {
                "title": "Der Hobbit",
                "authors": [{"name": "J.R.R. Tolkien"}],
                "narrators": [],
                "runtime_length_min": 660,
                "language": "German (DE)",
            }
        }
        result = json.loads(sleeve_audible.resolve("B08G9PRS1K"))

        self.assertEqual("german", result["tracks"][0]["language"])

    def test_a_catalogue_without_a_language_reports_none(self):
        result = json.loads(sleeve_audible.resolve("B08G9PRS1K"))

        self.assertEqual("", result["tracks"][0]["language"])

    def test_language_tokens_drop_their_region_and_case(self):
        for raw, expected in (
            ("English (US)", "english"),
            ("en-US", "en"),
            ("German", "german"),
            ("", ""),
            (None, ""),
        ):
            with self.subTest(raw=raw):
                self.assertEqual(expected, sleeve_audible._language(raw))

    def test_regional_audible_tlds_map_to_matching_api_hosts(self):
        regional_cases = (
            ("https://www.audible.co.uk/pd/B002V1OF70", "api.audible.co.uk", "B002V1OF70"),
            ("https://www.audible.de/pd/Der-Hobbit/B002V1OF70", "api.audible.de", "B002V1OF70"),
            ("https://www.audible.fr/pd/B002V1OF70", "api.audible.fr", "B002V1OF70"),
            ("https://www.audible.co.jp/pd/B002V1OF70", "api.audible.co.jp", "B002V1OF70"),
            ("https://www.audible.com.au/pd/B002V1OF70", "api.audible.com.au", "B002V1OF70"),
            ("https://www.audible.ca/pd/B002V1OF70", "api.audible.ca", "B002V1OF70"),
            ("https://www.audible.it/pd/B002V1OF70", "api.audible.it", "B002V1OF70"),
            ("https://www.audible.es/pd/B002V1OF70", "api.audible.es", "B002V1OF70"),
            ("https://www.audible.in/pd/B002V1OF70", "api.audible.in", "B002V1OF70"),
            ("https://www.audible.com.br/pd/B002V1OF70", "api.audible.com.br", "B002V1OF70"),
            # Unknown/spoofed hosts fall back safely to api.audible.com.
            ("https://evil-audible.co.uk/pd/B002V1OF70", "api.audible.com", "B002V1OF70"),
            ("https://audible.co.uk.evil.com/pd/B002V1OF70", "api.audible.com", "B002V1OF70"),
        )
        for url, expected_host, asin in regional_cases:
            with self.subTest(url=url):
                self.api.calls.clear()
                result = json.loads(sleeve_audible.resolve(url))
                self.assertTrue(result["ok"], result)
                self.assertEqual(
                    [
                        f"https://{expected_host}/1.0/catalog/products/{asin}"
                        "?response_groups=product_attrs,contributors,product_desc"
                    ],
                    self.api.calls,
                )

    def test_empty_or_missing_product_returns_error(self):
        for payload in (
            {},
            {"product": None},
            {"product": {}},
            {"product": {"title": ""}},
            {"product": {"title": "   "}},
        ):
            with self.subTest(payload=payload):
                sleeve_audible._api = lambda url, p=payload: p
                result = json.loads(
                    sleeve_audible.resolve("https://www.audible.com/pd/B002V1OF70")
                )
                self.assertFalse(result["ok"])
                self.assertEqual("empty audible product", result["error"])

    def test_keyword_search_with_blank_title_returns_error(self):
        sleeve_audible._api = lambda url: {"products": [{"title": "  "}]}
        result = json.loads(sleeve_audible.resolve("", "Dune"))
        self.assertFalse(result["ok"])
        self.assertEqual("empty audible product", result["error"])


if __name__ == "__main__":
    unittest.main()
