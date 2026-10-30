# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://h.mg-renders.net/ and https://a.mg-renders.net/"""

from .common import Extractor, Message
from .blogger import original
from .. import text

BASE_PATTERN = r"(?:https?://)?([ah])\.mg\-renders\.net"


class MgrendersExtractor(Extractor):
    """Base class for mgrenders extractors"""
    category = "mgrenders"
    root = "https://a.mg-renders.net"
    cookies_domain = ".mg-renders.net"

    def _init(self):
        self.root = f"https://{self.groups[0]}.mg-renders.net"


class MgrendersPostExtractor(MgrendersExtractor):
    subcategory = "post"
    filename_fmt = "{date:%Y-%m-%d} {title}.{extension}"
    archive_fmt = "{post_url}"
    pattern = BASE_PATTERN + r"(/\d{4}/\d{2}/[\w-]+)"
    example = "https://a.mg-renders.net/2020/12/SLUG-12345.html"

    def items(self):
        url = f"{self.root}{self.groups[1]}.html"
        page = self.request(url).text
        extr = text.extract_from(page)

        post = {
            "title": text.unescape(extr("<meta content='", "'")),
            "date": self.parse_datetime_iso(extr(
                "class='published' title='", "'")),
            "url" : text.unescape(text.extr(extr(
                "id='download-o'", "</div>"), "href='", "'")),
            "tags": text.split_html(extr("id='post-labels'", "</span>"))[1:],
            "post_url": url,
        }

        if m := text.re(r"(?i)(?:(.+) - )?(.+) Render(?: (?:.*\[(.+)\])?"
                        r"(?:.*#(\d+))?)?").match(post["title"]):
            post["series"], post["character"], post["artist"], post["id"] = \
                m.groups()

        url = original(text.ensure_http_scheme(post["url"]))
        text.nameext_from_url(url, post)
        yield Message.Directory, "", post
        yield Message.Url, url, post


class MgrendersSearchExtractor(MgrendersExtractor):
    subcategory = "search"
    pattern = BASE_PATTERN + r"/search/label/([^/?#]+)"
    example = "https://a.mg-renders.net/search/label/LABEL"

    def items(self):
        url = f"{self.root}/search/label/{self.groups[1]}?&max-results=48"
        data = {"_extractor": MgrendersPostExtractor}
        urls = text.re(r"<a class='m?title' href='([^']+)").findall
        while True:
            page = self.request(url).text

            for url in urls(page):
                yield Message.Queue, text.unescape(url), data

            pos = page.find(">Next &#187;<")
            if pos < 0:
                break
            url = text.unescape(text.rextr(page, "href='", "'", pos))
