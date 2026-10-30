# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann & varenc
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractor for https://nuremberg.law.harvard.edu/"""

from .common import GalleryExtractor
from .. import text


class HarvardlawnurembergDocumentExtractor(GalleryExtractor):
    """Extractor for documents on nuremberg.law.harvard.edu"""
    category = "harvardlawnuremberg"
    subcategory = "document"
    root = "https://nuremberg.law.harvard.edu"
    directory_fmt = ("{category}", "{document_id} {slug}")
    filename_fmt = "{document_id}_{num:>03}.{extension}"
    archive_fmt = "{document_id}_{num}"
    pattern = (r"(?:https?://)?nuremberg\.law\.harvard\.edu"
               r"/documents/(\d+)-([^/?#]+)")
    example = "https://nuremberg.law.harvard.edu/documents/12345-SLUG"

    def __init__(self, match):
        self.document_id = match[1]
        self.slug = match[2]

        # use '?mode=image' to force image mode,
        # even though its the default, to override
        # a possible '?mode=text' in the input URL
        url = (f"{self.root}/documents"
               f"/{self.document_id}-{self.slug}?mode=image")
        GalleryExtractor.__init__(self, match, url)

    def metadata(self, page):
        return {
            "document_id": self.document_id,
            "slug"       : self.slug,
            "title"      : text.unescape(
                text.extr(page, 'aria-level="1">', "</h1>")
            ),
        }

    def images(self, page):
        results = []
        for div in text.extract_iter(page, '<div data-screen-url="', "</div>"):
            extr = text.extract_from(div)
            results.append((
                extr('data-full-url="', '"'),
                {
                    "width" : text.parse_int(extr('data-width="', '"')),
                    "height": text.parse_int(extr('data-height="', '"')),
                },
            ))
        return results
