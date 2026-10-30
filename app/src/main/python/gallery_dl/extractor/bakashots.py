# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://compare.bakashots.me/"""

from .common import GalleryExtractor, Extractor, Message
from .. import text, util

BASE_PATTERN = r"(?:https?://)?compare\.bakashots\.me"


class BakashotsExtractor(Extractor):
    """Base class for bakashots extractors"""
    category = "bakashots"
    root = "https://compare.bakashots.me"


class BakashotsComparisonExtractor(GalleryExtractor, BakashotsExtractor):
    """Extractor for bakashots comparisons"""
    subcategory = "comparison"
    directory_fmt = ("{category}", "{set_id} {title}")
    filename_fmt = "{num:>02}.{extension}"
    archive_fmt = "{set_id}_{num}"
    pattern = BASE_PATTERN + r"(/compare\.php\?setId=(\d+))"
    example = "https://compare.bakashots.me/compare.php?setId=12345"

    def metadata(self, page):
        extr = text.extract_from(page)
        self.data = util.json_loads(extr("var G_imageLists =", ";\n"))

        return {
            "set_id": text.parse_int(self.groups[1]),
            "title" : text.unescape(extr("<title>", "<")),
            "date"  : self.parse_datetime(extr(
                ">Added: ", " ("), "%d/%m/%Y at %H:%M:%S"),
        }

    def images(self, page):
        return [
            (self.root + image["url"], {
                **image,
                "comparison_id": comparison.get("comparison_id"),
                "extension": "png",
            })
            for comparison in self.data
            for image in comparison["images"]
        ]


class BakashotsSearchExtractor(BakashotsExtractor):
    """Extractor for bakashots search results"""
    subcategory = "search"
    pattern = BASE_PATTERN + r"/search\.php\?([^#]+)"
    example = "https://compare.bakashots.me/search.php?searchTherms=QUERY"

    def items(self):
        data = {"_extractor": BakashotsComparisonExtractor}
        base = self.root + "/compare.php?setId="

        url = self.root + "/search.php"
        params = text.parse_query(self.groups[0])
        params["page"] = text.parse_int(params.get("page"), 0)

        while True:
            page = self.request(url, params=params).text

            for set_id in text.extract_iter(
                    page, 'href="/compare.php?setId=', '"'):
                yield Message.Queue, base + set_id, data

            if ">Next >><" not in page:
                break
            params["page"] += 1
