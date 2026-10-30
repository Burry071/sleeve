# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://artfight.net/"""

from .common import Extractor, Message, Dispatch
from .. import text, util

BASE_PATTERN = r"(?:https?://)?(?:www\.)?artfight\.net"
USER_PATTERN = BASE_PATTERN + r"/~([^/?#]+)"


class ArtfightExtractor(Extractor):
    """Base class for artfight extractors"""
    category = "artfight"
    root = "https://artfight.net"
    cookies_domain = ".artfight.net"
    directory_fmt = ("{category}", "{artist}", "{type!c}s")
    filename_fmt = "{id}_{num}_{title}.{extension}"
    archive_fmt = "{id}_{num}"
    per_page = 30
    page_start = 1
    offset = 0
    request_interval = (0.5, 1.5)
    tls12 = False  # CF

    def items(self):
        posts = self.posts()
        if self.offset:
            util.advance(posts, self.offset)
        for post_url in posts:
            try:
                post, files = self._extract(post_url)
            except Exception as exc:
                self.log.traceback(exc)
                self.log.warning("Failed to process '%s' (%s: %s)",
                                 post_url, exc.__class__.__name__, exc)
                continue

            yield Message.Directory, "", post
            for post["num"], url in enumerate(files, 1):
                text.nameext_from_url(url, post)
                yield Message.Url, url, post

    def skip_posts(self, num):
        pages, self.offset = divmod(num, self.per_page)
        self.page_start += pages
        return num

    def posts(self):
        user = self.groups[0]
        cat = self.__class__.subcategory
        url = f"{self.root}/~{user}/{cat}?page={self.page_start}"
        begin = f'class="profile-{cat}-body'
        end = 'class="d-flex justify-content-center'
        self.kwdict["username"] = text.unquote(user)

        while True:
            page = self.request(url).text

            items = text.extr(page, begin, end)
            yield from text.extract_iter(items, '<a href="', '"')

            link = text.iextr(page, 'rel="next"', "<", ">")
            if url := text.extr(link, 'href="', '"'):
                url = text.unescape(url)
            else:
                break

    def _extract(self, url):
        html = self.request(url).text
        extr = text.extract_from(html)
        _, type, name = url.rsplit("/", 2)
        title, _, artist = extr(' title="', '"').rpartition(" by ")

        post = {
            "page_url": url,
            "id"    : name[:name.rfind(".")],
            "type"  : type,
            "title" : text.unescape(title),
            "artist": text.unescape(artist),
        }

        if type == "attack":
            date = extr(">On: </strong>", "<")
            imgs = extr("<!-- Attack main image -->", "<!--")
            dscr = extr("description -->", "<!--")
            post["from"] = text.remove_html(extr(">From:</td>", "</tr>"))
            post["to"] = text.remove_html(extr(">To:</td>", "</tr>"))
            post["team"] = text.remove_html(extr(">Team:</td>", "</tr>"))
        else:
            date = extr(">Created: </strong>", "<")
            imgs = extr("<!-- Character main image -->", "<!--")
            dscr = extr("description -->", "<!--")
            post["tags"] = text.split_html(extr(">Tags<", "</div>"))[1:]

        files = list(text.extract_iter(
            imgs, '<a target="_blank" href="', '"'))[::2]
        post["description"] = dscr[
            dscr.find('class="fr-view">')+16:dscr.rfind("</div>\n", 0, -12)]
        post["date"] = self.parse_datetime(date, "%d %B %Y %I:%M:%S %p")
        post["count"] = len(files)
        return post, files


class ArtfightUserExtractor(Dispatch, ArtfightExtractor):
    pattern = USER_PATTERN + "$"
    example = "https://artfight.net/~USER"

    def items(self):
        base = f"{self.root}/~{self.groups[0]}/"
        return self._dispatch_extractors((
            (ArtfightCharactersExtractor, base + "characters"),
            (ArtfightAttacksExtractor   , base + "attacks"),
            (ArtfightDefensesExtractor  , base + "defenses"),
        ), ("characters", "attacks", "defenses"))


class ArtfightCharactersExtractor(ArtfightExtractor):
    subcategory = "characters"
    directory_fmt = ("{category}", "{username}", "Characters")
    archive_fmt = "c{id}_{num}"
    pattern = USER_PATTERN + r"/characters"
    example = "https://artfight.net/~USER/characters"


class ArtfightAttacksExtractor(ArtfightExtractor):
    subcategory = "attacks"
    directory_fmt = ("{category}", "{username}", "Attacks")
    archive_fmt = "a{id}_{num}"
    pattern = USER_PATTERN + r"/attacks"
    example = "https://artfight.net/~USER/attacks"


class ArtfightDefensesExtractor(ArtfightExtractor):
    subcategory = "defenses"
    directory_fmt = ("{category}", "{username}" "Defenses")
    archive_fmt = "d{id}_{num}"
    pattern = USER_PATTERN + r"/defenses"
    example = "https://artfight.net/~USER/defenses"


class ArtfightPostExtractor(ArtfightExtractor):
    subcategory = "post"
    pattern = BASE_PATTERN + r"(/(?:character|attack)/\d+(?:\.[^/?#]+)?)"
    example = "https://artfight.net/attack/12345.SLUG"
    skip_posts = None

    def posts(self):
        self.kwdict["username"] = ""
        return (self.root + self.groups[0],)


class ArtfightAssetsExtractor(ArtfightExtractor):
    subcategory = "assets"
    directory_fmt = ("{category}", "Art Assets")
    filename_fmt = "{num:>03}_{filename}.{extension}"
    archive_fmt = "art_assets/{filename}"
    pattern = BASE_PATTERN + r"/info/art-assets"
    example = "https://artfight.net/info/art-assets"
    skip_posts = None

    def items(self):
        url = self.root + "/info/art-assets"
        page = self.request(url).text
        extr = text.extract_from(page)
        urls = list(util.unique(text.re(
            r'<img [^>]*src="([^"]+)').findall(page)))

        data = {
            "id"    : 0,
            "count" : len(urls),
            "title" : text.unescape(extr("<h1>", "<")),
            "date"  : self.parse_datetime(
                extr("</a></strong>, ", "<"), "%d %B %Y %I:%M:%S %p"),
            "page_url"   : url,
        }

        yield Message.Directory, "", data
        for num, url in enumerate(urls, 1):
            data["num"] = num
            data["file"] = url
            text.nameext_from_url(url, data)
            yield Message.Url, url, data
