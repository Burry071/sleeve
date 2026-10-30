# -*- coding: utf-8 -*-

# Copyright 2022-2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://webmshare.com/"""

from .common import Extractor, Message
from .. import text


class WebmshareExtractor(Extractor):
    """Base class for webmshare extractors"""
    category = "webmshare"
    root = "https://webmshare.com"

    def _init(self):
        if not self.cookies.get("is_adult", domain="webmshare.com"):
            self.cookies.set(
                "is_adult", "eyJpdiI6Iis4UmlSK1wvM3ZRTVwvQ0lEVmtaU3hFdz09Iiwid"
                "mFsdWUiOiJKR0tRUytMeHh1ekhZRTN2STg3VEV3PT0iLCJtYWMiOiI5OTE0N2"
                "VhMGNmOTM3ZDc5OTZkNzNiOWI2NzI2YWExOTRhYjBkNDlkZTJhMjk5YzVlZDc"
                "xOTRmNGM2NThmNzM2In0%3D", domain="webmshare.com")


class WebmshareSearchExtractor(WebmshareExtractor):
    """Extractor for webmshare search results"""
    subcategory = "search"
    pattern = r"(?:https?://)?webmshare\.com/results\?q=([^&#]+)"
    example = "https://webmshare.com/results?q=QUERY"

    def items(self):
        query = self.groups[0]
        tags = text.unquote(query)
        page = self.request(f"{self.root}/results?q={query}").text

        if not tags.strip():
            self.log.warning("Empty search query")

        data = {
            "_extractor" : WebmshareVideoExtractor,
            "search_tags": tags,
        }

        base = self.root + "/"
        for video_id in text.extract_iter(page, '<a href="/', '"'):
            yield Message.Queue, base + video_id, data


class WebmshareVideoExtractor(WebmshareExtractor):
    """Extractor for webmshare videos"""
    subcategory = "video"
    filename_fmt = "{id}{title:? //}.{extension}"
    archive_fmt = "{id}"
    pattern = (r"(?:https?://)?(?:s\d+\.)?webmshare\.com"
               r"/(?:play/|download-webm/)?(\w{3,})")
    example = "https://webmshare.com/_ID_"

    def items(self):
        video_id = self.groups[0]
        url = f"{self.root}/{video_id}"
        extr = text.extract_from(self.request(url).text)

        title = extr('property="og:title" content="', '"')
        if not title:
            url_r18 = self.root + "/is_adult"
            data = {
                "url_to_go": text.unescape(extr(
                    'name="url_to_go" value="', '"')),
                "_token"   : text.unescape(extr(
                    'name="_token" value="', '"')),
            }
            response = self.request(url_r18, method="POST", data=data)
            extr = text.extract_from(response.text)
            title = extr('property="og:title" content="', '"')

        data = {
            "title": text.unescape(title.rpartition(" — ")[0]),
            "thumb": "https:" + extr('property="og:image" content="', '"'),
            "url"  : "https:" + extr('property="og:video" content="', '"'),
            "width": text.parse_int(extr(
                'property="og:video:width" content="', '"')),
            "height": text.parse_int(extr(
                'property="og:video:height" content="', '"')),
            "date" : self.parse_datetime(extr(
                "<small>Added ", "<"), "%B %d, %Y"),
            "views": text.parse_int(extr('glyphicon-eye-open"></span>', '<')),
            "id"       : video_id,
            "filename" : video_id,
            "extension": "webm",
        }

        if data["title"] == "webmshare":
            data["title"] = ""

        yield Message.Directory, "", data
        yield Message.Url, data["url"], data
