# -*- coding: utf-8 -*-

# Copyright 2025-2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://audiochan.com/"""

from .common import Extractor, Message
from .. import text

BASE_PATTERN = r"(?:https?://)?(?:www\.)?audiochan\.com"


class AudiochanExtractor(Extractor):
    """Base class for audiochan extractors"""
    category = "audiochan"
    root = "https://audiochan.com"
    root_api = "https://api.audiochan.com"
    directory_fmt = ("{category}", "{user[display_name]}")
    filename_fmt = "{title} ({slug}).{extension}"
    archive_fmt = "{audioFile[id]}"

    def _init(self):
        self.user = False
        self.headers_api = {
            "content-type": "application/json",
        }
        self.headers_dl = {
            "Accept": "audio/webm,audio/ogg,audio/wav,audio/*;q=0.9,"
                      "application/ogg;q=0.7,video/*;q=0.6,*/*;q=0.5",
            "Sec-Fetch-Dest" : "audio",
            "Sec-Fetch-Mode" : "no-cors",
            "Sec-Fetch-Site" : "same-site",
            "Accept-Encoding": "identity",
        }

    def items(self):
        for post in self.posts():
            post["date"] = self.parse_datetime_iso(post["created_at"])
            post["date_updated"] = self.parse_datetime_iso(post["updated_at"])

            if file := post.get("audioFile"):
                post["_http_headers"] = self.headers_dl
                post["description"] = self._extract_description(
                    post["description"])
            else:
                post["description"] = post.pop("teaser", "")

            tags = []
            for tag in post["tags"]:
                if "tag" in tag:
                    tag = tag["tag"]
                tags.append(f"{tag['category']}:{tag['name']}")
            post["tags"] = tags

            if self.user:
                for credit in post["credits"]:
                    if user := credit.get("user"):
                        post["user"] = user
                        break

            yield Message.Directory, "", post
            if file:
                text.nameext_from_name(file["filename"], post)
                yield Message.Url, self._extract_url(post), post

    def request_api(self, endpoint, params=None):
        url = self.root_api + endpoint
        return self.request_json(url, params=params, headers=self.headers_api)

    def _pagination(self, endpoint, params, key=None):
        params["page"] = 1
        params["limit"] = "12"

        while True:
            data = self.request_api(endpoint, params)
            if key is not None:
                data = data[key]

            yield from data["data"]

            if not data["has_more"]:
                break

            try:
                if cursor := data["meta"]["pagination"].get("next_cursor"):
                    params["cursor"] = cursor
                    params.pop("page", None)
                else:
                    params["page"] += 1
            except Exception:
                break

    def _extract_url(self, post):
        file = post["audioFile"]
        if url := file.get("url"):
            return url

        base = f"{self.root_api}/audios/{post['id']}"
        data = {"file_id": file.get("source_audio_file_id") or file["id"]}
        data["play_intent"] = self.request_json(
            base + "/play-intent", method="POST", headers=self.headers_api,
            json=data)["play_intent"]
        return self.request_json(
            base + "/stream-url", method="POST", headers=self.headers_api,
            json=data)["url"]

    def _extract_description(self, description, texts=None):
        if texts is None:
            texts = []

        if "text" in description:
            texts.append(description["text"])
        elif "content" in description:
            for desc in description["content"]:
                self._extract_description(desc, texts)

        return texts


class AudiochanAudioExtractor(AudiochanExtractor):
    subcategory = "audio"
    pattern = BASE_PATTERN + r"/a/([^/?#]+)"
    example = "https://audiochan.com/a/SLUG"

    def posts(self):
        self.user = True
        audio = self.request_api("/audios/slug/" + self.groups[0])
        return (audio,)


class AudiochanUserExtractor(AudiochanExtractor):
    subcategory = "user"
    pattern = BASE_PATTERN + r"/u/([^/?#]+)"
    example = "https://audiochan.com/u/USER"

    def posts(self):
        endpoint = "/users/" + self.groups[0]
        self.kwdict["user"] = self.request_api(endpoint)["data"]

        params = {
            "type": "all",
            "content_mode": "all",
            "sort": "new",
        }
        return self._pagination(endpoint + "/content", params)


class AudiochanCollectionExtractor(AudiochanExtractor):
    subcategory = "collection"
    pattern = BASE_PATTERN + r"/c/([^/?#]+)"
    example = "https://audiochan.com/c/SLUG"

    def posts(self):
        slug = self.groups[0]
        endpoint = "/playlists/" + slug
        self.kwdict["collection"] = col = self.request_api(endpoint)
        col.pop("audios", None)
        col.pop("items", None)

        endpoint = f"/playlists/slug/{slug}/audios"
        return self._pagination(endpoint, {})


class AudiochanSearchExtractor(AudiochanExtractor):
    subcategory = "search"
    pattern = BASE_PATTERN + r"/search/?\?([^#]+)"
    example = "https://audiochan.com/search?q=QUERY"

    def posts(self):
        self.user = True

        params = text.parse_query(self.groups[0])
        type = params.pop("tab", "audios")
        params.setdefault("type", type)
        params.setdefault("sort", "new")
        params["count_mode"] = "none"

        self.kwdict["search_tags"] = params.get("q")
        return self._pagination("/search", params, type)
