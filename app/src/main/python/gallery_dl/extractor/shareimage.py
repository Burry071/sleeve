# -*- coding: utf-8 -*-

# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://www.share-image.com/"""

from .common import GalleryExtractor
from .. import text


class ShareimageGalleryExtractor(GalleryExtractor):
    """Extractor for image galleries from share-image.com"""
    category = "shareimage"
    root = "https://www.share-image.com"
    pattern = r"(?:https?://)?(?:www\.)?share-image\.com(/(\d+)[^/?#]*)"
    example = "https://www.share-image.com/12345-TITLE"

    def metadata(self, page):
        self.schema = schema = self._extract_jsonld(page)
        return {
            "gallery_id" : text.parse_int(self.groups[1]),
            "gallery_url": schema.get("url"),
            "title"      : schema.get("name"),
        }

    def images(self, page):
        return [(url, None) for url in self.schema["image"]]
