# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://cara.app/"""

from .common import Extractor, Message
from .. import text

BASE_PATTERN = r"(?:https?://)?(?:www\.)?cara\.app"


class CaraExtractor(Extractor):
    """Base class for cara extractors"""
    category = "cara"
    root = "https://cara.app"
    root_cdn = "https://cdn.cara.app"
    directory_fmt = ("{category}", "{name} ({authorId})")
    filename_fmt = "{date}{title:? //} {num:>02} ({id}).{extension}"
    archive_fmt = "{file_id}"

    def items(self):
        for post in self.posts():
            imgs = [img for img in post.pop("images", ())
                    if img.get("order", 0) >= 0]
            imgs.sort(key=lambda img: img.get("order", 0))

            post["count"] = len(imgs)
            post["date"] = self.parse_datetime_iso(post["createdAt"])
            yield Message.Directory, "", post

            for post["num"], img in enumerate(imgs, 1):
                img["file_id"] = img.pop("id", None)
                src = img["src"]
                post.update(img)
                text.nameext_from_url(src, post)
                yield Message.Url, f"{self.root_cdn}/{src}", post


class CaraPostExtractor(CaraExtractor):
    subcategory = "post"
    pattern = BASE_PATTERN + r"/post/([0-9a-f-]{36})"
    example = "https://cara.app/post/01234567-89ab-cdef-0123-456789abcdef"

    def posts(self):
        url = f"{self.root}/api/posts/{self.groups[0]}"
        return (self.request_json(url)["data"],)


class CaraUserExtractor(CaraExtractor):
    subcategory = "user"
    pattern = BASE_PATTERN + r"/([\w-]+)"
    example = "https://cara.app/USER"

    def posts(self):
        url = "https://cara.app/api/posts/getAllByUser"
        params = {
            "slug": self.groups[0],
            "take": 15,
            "skip": None,
        }

        while True:
            data = self.request_json(url, params=params)

            if imgs := data.get("data"):
                yield from imgs
            else:
                break

            try:
                params.update(data["paging"])
                params["skip"] += params["take"]
            except Exception:
                break
