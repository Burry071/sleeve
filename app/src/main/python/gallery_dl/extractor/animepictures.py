# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://anime-pictures.net/"""

from . import booru
from .. import text
import collections

BASE_PATTERN = r"(?:https?://)?(?:www\.)?anime\-pictures\.net"


class AnimepicturesExtractor(booru.BooruExtractor):
    """Base class for animepictures extractors"""
    category = "animepictures"
    root = "https://anime-pictures.net"
    root_api = "https://api.anime-pictures.net/api"
    per_page = 80
    download_interval = 5.0
    request_interval = 1.0

    TAG_TYPES = {
        1: "character",
        2: "reference",
        3: "copyright",
        4: "author",
        5: "",
        6: "copyright_other",
        7: "object",
    }

    def _init(self):
        self.cookies.set("kira", "2", domain=".anime-pictures.net")

    def _file_url(self, post):
        md5 = post["md5"]
        return (f"https://oimages.anime-pictures.net/"
                f"{md5[:3]}/{md5}{post['ext']}")

    def _prepare(self, post):
        post["date"] = self.parse_datetime_iso(post["datetime"])

    def _tags(self, post, _):
        if "tags" not in post:
            data = self.request_json(f"{self.root_api}/v3/posts/{post['id']}")
            data.pop("post", None)
            post.update(data)

        tags = collections.defaultdict(list)
        for tag in post["tags"]:
            tag = tag["tag"]
            tags[tag["type"]].append(tag["tag"])
        types = self.TAG_TYPES
        for key, value in tags.items():
            post["tags_" + types[key]] = value

    def _pagination(self, url, params):
        params["page"] = text.parse_int(params.get("page"), self.page_start)

        while True:
            data = self.request_json(url, params=params)

            yield from data["posts"]

            if params["page"] >= data["max_pages"]:
                break
            params["page"] += 1

    def _pagination_scores(self, url, params):
        if "offset" in params:
            params["offset"] = text.parse_int(params["offset"])
        elif "page" in params:
            params["offset"] = text.parse_int(
                params.pop("page")) * self.per_page
        else:
            params["offset"] = self.page_start * self.per_page
        params.setdefault("limit", self.per_page)

        while True:
            data = self.request_json(url, params=params)

            for score in data["scores"]:
                post = score.pop("post")
                post.update(score)
                yield post

            if len(data["scores"]) < data["limit"]:
                break
            params["offset"] += data["limit"]


class AnimepicturesPostExtractor(AnimepicturesExtractor):
    subcategory = "post"
    archive_fmt = "{id}"
    pattern = BASE_PATTERN + r"/posts/(\d+)"
    example = "https://anime-pictures.net/posts/12345"

    def posts(self):
        url = f"{self.root_api}/v3/posts/{self.groups[0]}"
        data = self.request_json(url)
        post = data.pop("post")
        post.update(data)
        return (post,)


class AnimepicturesFavoriteExtractor(AnimepicturesExtractor):
    subcategory = "favorite"
    directory_fmt = ("{category}", "Favorites", "{favorite_id}")
    archive_fmt = "f_{favorite_id}_{id}"
    pattern = BASE_PATTERN + r"/posts\?(favorite_by=[^#]+)"
    example = "https://anime-pictures.net/posts?favorite_by=12345"

    def posts(self):
        url = self.root_api + "/v3/posts"
        params = text.parse_query(self.groups[0])
        self.kwdict["favorite_id"] = params["favorite_by"]
        self.kwdict["favorite_folder"] = params.get("favorite_folder", "")
        return self._pagination(url, params)


class AnimepicturesTagExtractor(AnimepicturesExtractor):
    subcategory = "tag"
    directory_fmt = ("{category}", "{search_tags}")
    archive_fmt = "t_{search_tags}_{id}"
    pattern = BASE_PATTERN + r"/posts\?([^#]+)"
    example = "https://anime-pictures.net/posts?search_tag=TAG"

    def posts(self):
        url = self.root_api + "/v3/posts"
        params = text.parse_query(self.groups[0])
        self.kwdict["search_tags"] = params.get("search_tag", "")
        return self._pagination(url, params)


class AnimepicturesStarsExtractor(AnimepicturesExtractor):
    subcategory = "stars"
    per_page = 50
    directory_fmt = ("{category}", "Stars")
    archive_fmt = "s_{id}"
    pattern = BASE_PATTERN + r"/stars(?:\?([^#]+))?"
    example = "https://anime-pictures.net/stars"

    def posts(self):
        url = self.root_api + "/v3/scores"
        params = text.parse_query(self.groups[0])
        return self._pagination_scores(url, params)
