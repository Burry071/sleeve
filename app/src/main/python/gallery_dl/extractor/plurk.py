# -*- coding: utf-8 -*-

# Copyright 2019-2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://www.plurk.com/"""

from .common import Extractor, Message
from .. import text, util, dt

BASE_PATTERN = r"(?:https?://)?(?:www\.)?plurk\.com"


class PlurkExtractor(Extractor):
    """Base class for plurk extractors"""
    category = "plurk"
    root = "https://www.plurk.com"
    directory_fmt = ("{category}", "{user_id}")
    filename_fmt = "{plurk_id}_{num}.{extension}"
    archive_fmt = "{plurk_id}_{num}"
    request_interval = (0.5, 1.5)

    def items(self):
        urls = self._urls_ex if self.config("comments", False) else self._urls
        replurk = self.config("replurk", False)
        external = self.config("external", False)
        internal = (
            "https://images.plurk.com/",
            "https://imgs.plurk.com/",
            "http://images.plurk.com/",
            "http://imgs.plurk.com/",
        )

        for plurk in self.plurks():
            if not replurk and plurk.get("replurked"):
                continue
            plurk["plurk_id_base36"] = util.b36encode(plurk.get("plurk_id"))
            plurk["date"] = self.parse_datetime(
                plurk.get("posted"), "%a, %d %b %Y %H:%M:%S %Z")
            if plurk.get("last_edited"):
                plurk["date_edited"] = self.parse_datetime(
                    plurk.get("last_edited"), "%a, %d %b %Y %H:%M:%S %Z")
            plurk.pop("favorers", None)
            plurk.pop("replurkers", None)

            num = 1
            yield Message.Directory, "", plurk
            for url in urls(plurk):
                data = plurk.copy()
                data["num"] = num
                if url.startswith(internal):
                    yield Message.Url, url, text.nameext_from_url(url, data)
                    num += 1
                elif external:
                    text.nameext_from_url(url, data)
                    yield Message.Queue, url, data
                    num += 1

    def _urls(self, obj):
        """Extract URLs from a 'plurk' object"""
        return text.extract_iter(obj["content"], ' href="', '"')

    def _urls_ex(self, plurk):
        """Extract URLs from a 'plurk' and its comments"""
        yield from self._urls(plurk)
        for comment in self._comments(plurk):
            yield from self._urls(comment)

    def _comments(self, plurk):
        """Return an iterable with a 'plurk's comments"""
        url = "https://www.plurk.com/Responses/get"
        data = {"plurk_id": plurk["id"], "count": "200"}
        headers = {"X-Requested-With": "XMLHttpRequest"}

        while True:
            info = self.request_json(
                url, method="POST", headers=headers, data=data)
            yield from info["responses"]
            if not info["has_newer"]:
                return
            elif info["has_newer"] < 200:
                del data["count"]
            data["from_response_id"] = info["responses"][-1]["id"] + 1

    def _load(self, data):
        if not data:
            raise self.exc.NotFoundError("user")
        return util.json_loads(
            text.re(r"new Date\(([^)]+)\)").sub(r"\1", data))


class PlurkTimelineExtractor(PlurkExtractor):
    """Extractor for a plurk timeline"""
    subcategory = "timeline"
    pattern = BASE_PATTERN + r"/(?!p/)([^/?#]+)"
    example = "https://www.plurk.com/USER"

    def plurks(self):
        url = f"{self.root}/{self.groups[0]}"
        page = self.request(url).text
        user_id, pos = text.extract(page, '"page_user": {"id":', ',')
        plurks = self._load(text.extract(page, "_PLURKS = ", ";\n", pos)[0])

        headers = {"Referer": url, "X-Requested-With": "XMLHttpRequest"}
        data = {"user_id": user_id.strip()}
        url = "https://www.plurk.com/TimeLine/getPlurks"

        while plurks:
            yield from plurks

            offset = dt.parse(plurks[-1]["posted"], "%a, %d %b %Y %H:%M:%S %Z")
            data["offset"] = offset.strftime("%Y-%m-%dT%H:%M:%S.000Z")
            plurks = self.request_json(
                url, method="POST", headers=headers, data=data)["plurks"]


class PlurkPostExtractor(PlurkExtractor):
    """Extractor for a plurk post"""
    subcategory = "post"
    pattern = BASE_PATTERN + r"/p/([^/?#]+)"
    example = "https://www.plurk.com/p/12345"

    def plurks(self):
        url = f"{self.root}/p/{self.groups[0]}"
        page = self.request(url).text
        user, pos = text.extract(page, " GLOBAL=", "\n")
        data, pos = text.extract(page, "plurk =", ";\n", pos)

        data = self._load(data)
        try:
            data["user"] = self._load(user)["page_user"]
        except Exception:
            self.log.warning("%s: Failed to extract 'user' data",
                             self.groups[0])
        return (data,)
