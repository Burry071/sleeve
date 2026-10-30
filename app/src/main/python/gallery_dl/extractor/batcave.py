# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://batcave.biz/"""

from .common import ChapterExtractor, MangaExtractor
from .. import text, util

BASE_PATTERN = r"(?:https?://)?(?:www\.)?batcave\.biz"


class BatcaveBase():
    """Base class for batcave extractors"""
    category = "batcave"
    root = "https://batcave.biz"

    def _extract_window_data(self, html):
        data = text.extr(html, ">window.__DATA__ = {", "};</script>")
        return util.json_loads(f"{{{data}}}")


class BatcaveIssueExtractor(BatcaveBase, ChapterExtractor):
    """Extractor for batcave comic issues"""
    subcategory = "issue"
    directory_fmt = ("{category}", "{comic}", "{issue|issue_string:>03}")
    filename_fmt = "{comic}_{issue|issue_string:>03}_{page:>03}.{extension}"
    archive_fmt = "{issue_id}_{page}"
    pattern = BASE_PATTERN + r"(/reader/\d+/\d+)"
    example = "https://batcave.biz/reader/12345/123"

    def metadata(self, page):
        self.data = data = self._extract_window_data(page)

        comic = data["post_title"]
        comic_id = data["news_id"]
        chapter_id = data["chapter_id"]

        for chapter in data["chapters"]:
            if chapter.get("id") == chapter_id:
                if issue := chapter.get("title"):
                    _, sep, inum = issue.rpartition(" Issue #")
                    if sep:
                        issue = "Issue #" + inum
                        inum = text.parse_int(inum)
                    else:
                        issue = issue[issue.find(")")+2:]
                        inum = 0
                break
        else:
            inum = 0
            issue = ""

        return {
            "comic"   : comic,
            "comic_id": comic_id,
            "issue"   : inum,
            "issue_id": chapter_id,
            "issue_string": issue,
            "lang"    : text.extr(page, '"inLanguage":"', '"'),
            "date"    : self.parse_datetime_iso(text.extr(
                page, '"datePublished":"', '"'))
        }

    def images(self, page):
        return [(url, None) for url in self.data["images"]]


class BatcaveComicExtractor(BatcaveBase, MangaExtractor):
    """Extractor for batcave comics"""
    subcategory = "comic"
    chapterclass = BatcaveIssueExtractor
    pattern = BASE_PATTERN + r"(/\d+(?:-[\w-]*)?\.html)"
    example = "https://batcave.biz/12345-SLUG.html"

    def chapters(self, page):
        data = self._extract_window_data(page)
        base = f"{self.root}/reader/{data['news_id']}/"
        return [
            (base + str(chapter["id"]), chapter)
            for chapter in data["chapters"]
        ]
