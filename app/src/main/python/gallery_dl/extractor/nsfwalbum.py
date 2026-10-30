# -*- coding: utf-8 -*-

# Copyright 2019-2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://nsfwalbum.com/"""

from .common import GalleryExtractor
from .. import text, util


class NsfwalbumExtractor(GalleryExtractor):
    """Base class for nsfwalbum extractors"""
    category = "nsfwalbum"
    root = "https://nsfwalbum.com"
    archive_fmt = "{id}"
    referer = False

    def images(self, page):
        iframe = self.root + "/iframe_image.php?id="
        backend = self.root + "/backend.php"
        retries = self._retries

        for image_id in self.image_ids(page):
            spirit = None
            tries = 0

            while tries <= retries:
                try:
                    if not spirit:
                        spirit = self._annihilate(text.extract(
                            self.request(iframe + image_id).text,
                            'giraffe.annihilate("', '"')[0])
                        params = {"spirit": spirit, "photo": image_id}
                    data = self.request_json(backend, params=params)
                    break
                except Exception:
                    tries += 1
            else:
                self.log.warning("Unable to fetch image %s", image_id)
                continue

            yield data[0], {
                "id"    : text.parse_int(image_id),
                "width" : text.parse_int(data[1]),
                "height": text.parse_int(data[2]),
                "_http_validate": self._validate_response,
                "_fallback": (f"{self.root}/imageProxy.php"
                              f"?photoId={image_id}&spirit={spirit}",),
            }

    def _validate_response(self, response):
        return not response.url.endswith(
            ("/no_image.jpg", "/placeholder.png", "/error.jpg"))

    def _annihilate(self, value, base=6):
        return "".join(
            chr(ord(char) ^ base)
            for char in value
        )


class NsfwalbumAlbumExtractor(NsfwalbumExtractor):
    """Extractor for image albums on nsfwalbum.com"""
    subcategory = "album"
    filename_fmt = "{album_id}_{num:>03}_{id}.{extension}"
    directory_fmt = ("{category}", "{album_id} {title}")
    pattern = r"(?:https?://)?(?:www\.)?nsfwalbum\.com(/album/(\d+))"
    example = "https://nsfwalbum.com/album/12345"

    def skip_files(self, num):
        self.start += num
        return num

    def metadata(self, page):
        extr = text.extract_from(page)
        return {
            "album_id": text.parse_int(self.groups[1]),
            "title"   : text.unescape(extr('<h6>', '</h6>')),
            "models"  : text.split_html(extr('"models"> Models:', '</div>')),
            "studio"  : text.remove_html(extr('"models"> Studio:', '</div>')),
        }

    def image_ids(self, page):
        ids = text.extract_iter(page, 'data-img-id="', '"')
        if self.start > 1:
            util.advance(ids, self.start - 1)
        return ids


class NsfwalbumImageExtractor(NsfwalbumExtractor):
    """Extractor for single nsfwalbum.com images"""
    subcategory = "image"
    filename_fmt = "{id}.{extension}"
    directory_fmt = ("{category}",)
    pattern = r"(?:https?://)?(?:www\.)?nsfwalbum\.com/photo/(\d+)"
    example = "https://nsfwalbum.com/photo/12345"

    def metadata(self, _):
        return {}

    def image_ids(self, _):
        return (self.groups[0],)
