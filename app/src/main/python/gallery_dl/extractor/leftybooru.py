# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://lefty.pictures/"""

from . import booru
from .. import text

BASE_PATTERN = r"(?:https?://)?lefty\.pictures"


class LeftybooruExtractor(booru.BooruExtractor):
    """Base class for leftybooru extractors"""
    category = "leftybooru"
    basecategory = "shimmie2"
    root = "https://lefty.pictures"
    archive_fmt = "{id}"
    page_start = 1
    per_page = 24

    def _prepare(self, post):
        post["id"] = text.parse_int(post["id"])
        post["width"] = text.parse_int(post["width"])
        post["height"] = text.parse_int(post["height"])


class LeftybooruPostExtractor(LeftybooruExtractor):
    subcategory = "post"
    pattern = BASE_PATTERN + r"/post/view/(\d+)"
    example = "https://lefty.pictures/post/view/12345"

    def posts(self):
        post_id = self.groups[0]
        url = f"{self.root}/post/view/{post_id}"
        extr = text.extract_from(self.request(url).text)

        post = {
            "id"      : post_id,
            "tags"    : text.unescape(extr(
                "name='keywords' content='", "'")).split(", "),
            "file_url": extr("property='og:image' content='", "'"),
            "width"   : extr("property='og:image:width' content='", "'"),
            "height"  : extr("property='og:image:height' content='", "'"),
            "md5"     : extr("/_images/", "/"),
            "uploader": extr(">Uploader: <a href='/user/", "'"),
            "date"    : self.parse_datetime_iso(extr("datetime='", "'")),
            "size"    : text.parse_bytes(extr(">Size: ", "B")),
            "type"    : extr(">Type: ", "<"),
            "duration": extr(">Length: ", "<"),
            "rating"  : extr(">Rating: ", "</")[34:35].lower(),
            "score"   : extr(">Post Score: ", "<"),
            "source"  : text.remove_html(extr(
                ">Source</a></th><td><span class='view'><div>", "</")),
            "parent_id": extr(
                ">Parent</th><td><span class='view'>", "<"),
        }

        if post["source"] == "Unknown":
            post["source"] = None
        if post["parent_id"] == "None":
            post["parent_id"] = None

        return (post,)


class LeftybooruTagExtractor(LeftybooruExtractor):
    subcategory = "tag"
    directory_fmt = ("{category}", "{search_tags}")
    pattern = BASE_PATTERN + r"/post/list/(?:([^/?#]+)/)?(\d+)"
    example = "https://lefty.pictures/post/list/TAG/1"

    def posts(self):
        tags, pnum = self.groups
        pnum = text.parse_int(pnum, 1) + self.page_start - 1
        if tags is None:
            tags = ""
        self.kwdict["search_tags"] = text.unquote(tags)

        base = self.root + "/_images/"
        while True:
            page = self.request(f"{self.root}/post/list/{tags}/{pnum}").text
            extr = text.extract_from(page)

            while True:
                pid = extr("<a href='/post/view/", "'")
                if not pid:
                    break

                post = {
                    "id"    : pid,
                    "tags"  : text.unescape(extr("data-tags='", "'")),
                    "type"  : extr("data-mime='", "'"),
                    "rating": extr("data-rating='", "'"),
                    "width" : extr(" // ", "x"),
                    "height": extr("", " "),
                    "size"  : text.parse_bytes(extr("// ", "B")),
                    "md5"   : extr("/_thumbs/", "/"),
                }

                post["file_url"] = (
                    f"{base}{post['md5']}/{pid} - {post['tags']}."
                    f"{post['type'].rpartition('/')[2]}")
                post["tags"] = post["tags"].split(" ")

                yield post

            if "id='nextlink'" not in page:
                break
            pnum += 1
