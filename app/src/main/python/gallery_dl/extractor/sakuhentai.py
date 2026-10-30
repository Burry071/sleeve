# -*- coding: utf-8 -*-

# Copyright 2026 Shinei Nouzen
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://www.sakuhentai.net/"""

from .common import GalleryExtractor
from .. import text


class SakuhentaiGalleryExtractor(GalleryExtractor):
    """Extractor for image galleries from sakuhentai.net"""
    category = "sakuhentai"
    root = "https://www.sakuhentai.net"
    pattern = r"(?:https?://)?(?:www\.)?sakuhentai\.net/([^/?&#]+)/?$"
    example = "https://www.sakuhentai.net/GALLERY-SLUG/"

    def __init__(self, match):
        url = f"{self.root}/{match[1]}/"
        GalleryExtractor.__init__(self, match, url)

    def metadata(self, page):
        self.ld = ld = self._extract_jsonld(page)
        extr = text.extract_from(page)
        return {
            "gallery_id": text.parse_int(extr("?p=", "'")),
            "title"     : text.unescape(extr("entry-title\">", "<")),
            "anime"     : text.unescape(extr(
                "cat-serie\"><h2 title=\"", '"'))[:-7],
            "character" : text.unescape(extr(
                "cat-character\"><h2 title=\"", '"'))[:-7],
            "artist"    : text.unescape(extr(
                "support-artist\"><h2 title=\"", '"'))[:-7],
            "date": self.parse_datetime_iso(ld.get("datePublished"))
        }

    def images(self, _):
        return [(url, None) for url in self.ld["image"]]
