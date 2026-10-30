# -*- coding: utf-8 -*-

# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://whitekitten.art/"""

from .booru import BooruExtractor
from .common import Message
from .. import text
import collections

BASE_PATTERN = r"(?:https?://)?(?:www\.)?whitekitten\.art"


class WhitekittenExtractor(BooruExtractor):
    """Base class for whitekitten extractors"""
    category = "whitekitten"
    root = "https://whitekitten.art"
    filename_fmt = "{category}_{id}_{file[fileOrder]}.{extension}"
    archive_fmt = "{id}_{file[id]}"
    per_page = 100

    def _init(self):
        self.params = {"maxRating": self.config("max-rating", "explicit")}

    def items(self):
        for post in self.posts():
            self._prepare_post(post)
            files = post.pop("files", ())
            post["count"] = len(files)

            yield Message.Directory, "", post
            for post["num"], file in enumerate(files, 1):
                post["file"] = self._prepare_file(file)
                post["_fallback"] = \
                    (png,) if (png := file.get("pngUrl")) else ()
                url = file["mediaUrl"]
                text.nameext_from_url(url, post)
                yield Message.Url, url, post

    def request_post(self, post_id):
        return self.request_json(
            f"{self.root}/api/posts/{post_id}", params=self.params,
        )["data"]["post"]

    def _pagination(self, params):
        url = self.root + "/api/posts"
        params = {"filterMode": "hidden", **self.params, **params}

        while True:
            data = self.request_json(url, params=params)["data"]

            yield from (data.get("posts") or ())

            params["cursor"] = cursor = data.get("cursor")
            if not cursor:
                break

    def _prepare_post(self, post):
        # post-list endpoint returns summary rows without 'files'/'tags';
        # fetch detail to fill those in
        if "files" not in post:
            post.update(self.request_post(post["id"]))
        post["date"] = self.parse_datetime_iso(post["createdAt"])
        post["date_updated"] = self.parse_datetime_iso(post["updatedAt"])
        return self._split_tags(post, post.get("tags") or ())

    def _prepare_file(self, file):
        tags = file.get("tags") or []
        if extra := file.get("inheritedTags"):
            tags.extend(extra)
        if extra := file.get("impliedTags"):
            tags.extend(extra)

        seen = set()
        unique = []
        for tag in tags:
            tid = tag.get("id")
            if tid not in seen:
                seen.add(tid)
                unique.append(tag)

        return self._split_tags(file, unique)

    def _split_tags(self, obj, tags):
        names = []
        grouped = collections.defaultdict(list)

        for tag in tags:
            name = (tag.get("name") or "").rpartition(":")[2]
            names.append(name)
            grouped[tag.get("category") or "uncategorized"].append(name)

        names.sort()
        obj["tags"] = names
        for cat, tlist in grouped.items():
            tlist.sort()
            obj["tags_" + cat] = tlist
        return obj


class WhitekittenTagExtractor(WhitekittenExtractor):
    """Extractor for whitekitten tag searches"""
    subcategory = "tag"
    directory_fmt = ("{category}", "{search_tags}")
    pattern = BASE_PATTERN + r"/?(?:\?([^#]*))?$"
    example = "https://whitekitten.art/?q=TAG"

    def posts(self):
        params = text.parse_query(self.groups[0])
        self.kwdict["search_tags"] = params.get("q", "")
        return self._pagination(params)


class WhitekittenPostExtractor(WhitekittenExtractor):
    """Extractor for a single whitekitten post"""
    subcategory = "post"
    pattern = BASE_PATTERN + r"/posts/(\d+)"
    example = "https://whitekitten.art/posts/12345"

    def posts(self):
        return (self.request_post(self.groups[0]),)
