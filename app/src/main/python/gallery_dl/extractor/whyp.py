# -*- coding: utf-8 -*-

# Copyright 2025-2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://whyp.it/"""

from .common import Extractor, Message
from .. import text

BASE_PATTERN = r"(?:https?://)?(?:www\.)?whyp\.it"
PATH_PATTERN = r"(?:([^/?#]+-\w+)|(\d+)(?:/[^/?#]+)?)/?(?:\?([^#]+))?"


class WhypExtractor(Extractor):
    """Base class for whyp extractors"""
    category = "whyp"
    root = "https://whyp.it"
    root_api = "https://api.whyp.it"
    directory_fmt = ("{category}", "{user[username]} ({user[id]})")
    filename_fmt = "{id} {title}.{extension}"
    archive_fmt = "{id}"

    def _init(self):
        self.headers_api = {
            "Accept" : "application/json",
        }

    def items(self):
        for track in self.tracks():
            if url := track.get("lossless_url"):
                track["original"] = True
            else:
                url = track["lossy_url"]
                track["original"] = False

            if "created_at" in track:
                track["date"] = self.parse_datetime_iso(track["created_at"])

            yield Message.Directory, "", track
            yield Message.Url, url, text.nameext_from_url(url, track)


class WhypAudioExtractor(WhypExtractor):
    subcategory = "audio"
    pattern = f"{BASE_PATTERN}/tracks/{PATH_PATTERN}"
    example = "https://whyp.it/tracks/NAME-ID"

    def tracks(self):
        tid1, tid2, qs = self.groups
        url = f"{self.root_api}/api/tracks/{tid1 or tid2}"
        params = None if qs is None else text.parse_query(qs)
        data = self.request_json(url, params=params, headers=self.headers_api)
        return (data["track"],)


class WhypUserExtractor(WhypExtractor):
    subcategory = "user"
    pattern = f"{BASE_PATTERN}/users/{PATH_PATTERN}"
    example = "https://whyp.it/users/NAME-ID"

    def tracks(self):
        uid1, uid2, qs = self.groups

        url = f"{self.root_api}/api/users/{uid1 or uid2}/tracks"
        params = text.parse_query(qs)
        headers = self.headers_api

        while True:
            data = self.request_json(url, params=params, headers=headers)

            yield from data["tracks"]

            if not (cursor := data.get("next_cursor")):
                break
            params["cursor"] = cursor


class WhypCollectionExtractor(WhypExtractor):
    subcategory = "collection"
    pattern = f"{BASE_PATTERN}/collections/{PATH_PATTERN}"
    example = "https://whyp.it/collections/NAME-ID"

    def tracks(self):
        cid1, cid2, qs = self.groups

        url = f"{self.root_api}/api/collections/{cid1 or cid2}"
        params = None if qs is None else text.parse_query(qs)
        headers = self.headers_api
        self.kwdict["collection"] = collection = self.request_json(
            url, params=params, headers=headers)["collection"]

        url = f"{self.root_api}/api/collections/{collection['id']}/tracks"
        params = {"token": collection["token"]}
        data = self.request_json(url, params=params, headers=headers)
        return data["tracks"]
