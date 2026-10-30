# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://www.comicartfans.com/"""

from .common import Extractor, Message
from .. import text, util

BASE_PATTERN = r"(?i)(?:https?://)?(?:www\.)?comicartfans\.com"


class ComicartfansExtractor(Extractor):
    """Base class for comicartfans extractors"""
    category = "comicartfans"
    root = "https://www.comicartfans.com"
    page_start = 1
    per_page = 54
    parent = True
    browser = "chrome"  # cloudflare

    def items(self):
        data = {"_extractor": ComicartfansArtworkExtractor}
        base = self.root + "/"

        for path in self.works():
            yield Message.Queue, base + path, data

    def skip_children(self, num):
        pages = num // self.per_page
        self.page_start += pages
        return pages * self.per_page

    def _pagination(self, url, params):
        if "pm" in params:
            params["pm"] = self.page_start + text.parse_int(params["pm"], 1)-1
        else:
            params["pm"] = self.page_start

        needle = """\
<div class="card-thumbnail">
                    <a href="\
"""
        while True:
            page = self.request(url, params=params).text

            yield from text.extract_iter(page, needle, '"')

            pos = page.find(">Next &raquo;")
            if pos < 0 or page[pos-2] in '"#':
                break
            params["pm"] += 1


class ComicartfansArtworkExtractor(ComicartfansExtractor):
    subcategory = "artwork"
    directory_fmt = ("{category}", "{owner}")
    filename_fmt = "{id}{num:?_//} {title}.{extension}"
    archive_fmt = "{id}_{num}"
    pattern = BASE_PATTERN + r"/gallerypiece\.asp\?piece=(\d+)"
    example = "https://www.comicartfans.com/gallerypiece.asp?piece=12345"

    def items(self):
        iid = self.groups[0]
        url = f"{self.root}/gallerypiece.asp?piece={iid}"
        extr = text.extract_from(self.request(url, encoding="utf-8").text)

        work = {
            "id"      : text.parse_int(iid),
            "views"   : text.parse_int(extr('id="likecount-load">', "&")),
            "comments": text.parse_int(extr("-&nbsp;", "&")),
            "likes"   : text.parse_int(extr("-&nbsp;", "&")),
            "file_url": extr('<img src="', '"'),
            "additional" : extr(
                ">Additional Images</h5>", '<div style="clear: both;"></div>'),
            "location": text.unescape(extr(
                "<b>Location:</b>", "</a>").rpartition(">")[2]),
            "title"   : text.unescape(extr("<b>Title:</b>", "<").strip()),
            "artist"  : text.split_html(extr(
                "<b>Artist:</b>", "<br>").replace("&nbsp;", " "))[::2],
            "media_type" : extr("<b>Media Type:</b>", "<").lstrip(),
            "art_type": extr("<b>Art Type:</b>", "<").lstrip(),
            "sale_status": extr("<b>For Sale Status:</b>", "<").lstrip(),
            "date"    : self.parse_datetime(extr(
                "<b>Added to Site:</b>", "<").lstrip(), "%m/%d/%Y"),
            "description": text.unescape(extr(
                'content description-box">', "</div>").strip()),
            "owner_id": text.parse_int(extr(
                '>\n<a href="gallerydetail.asp?gcat=', '"')),
            "owner"   : text.unescape(extr(
                ">", "<").replace("&nbsp;", " ")).strip(),
            "owner_date" : self.parse_datetime(extr(
                "<b>Member Since:</b>", "<").lstrip(), "%B&nbsp;%Y"),
            "owner_website": extr("<b>Website:</b>	<a href='", "'"),
            "owner_country": text.extr(extr(
                '<b>Country:</b>', '"'), ">", "<").capitalize(),
        }

        if additional := work.pop("additional", None):
            additional = additional.split('<a href="')
            work["count"] = len(additional)
            del additional[0]
        else:
            work["count"] = 1

        yield Message.Directory, "", work

        url = work["file_url"]
        work["num"] = 1 if additional else 0
        # 403 Forbidden
        work["_http_headers"] = {"User-Agent": util.USERAGENT_CHROME}
        text.nameext_from_url(url, work)
        yield Message.Url, url, work

        if additional:
            for work["num"], file in enumerate(additional, 2):
                work["title"] = text.unescape(text.extr(
                    file, 'data-caption="', '"'))
                url = file[:file.find('"')]
                text.nameext_from_url(url, work)
                yield Message.Url, url, work


class ComicartfansGalleryExtractor(ComicartfansExtractor):
    subcategory = "gallery"
    pattern = BASE_PATTERN + r"/gallerydetail(?:search)?\.asp\?([^#]+)"
    example = "https://www.comicartfans.com/gallerydetail.asp?gcat=12345"

    def works(self):
        url = self.root + "/gallerydetailsearch.asp"
        params = text.parse_query(self.groups[0])
        if "order" not in params:
            params["order"] = "Date"
        return self._pagination(url, params)


class ComicartfansSearchExtractor(ComicartfansExtractor):
    subcategory = "search"
    pattern = BASE_PATTERN + r"/searchresult\.asp\?([^#]+)"
    example = "https://www.comicartfans.com/searchresult.asp?QUERY"

    def works(self):
        url = self.root + "/searchresult.asp"
        params = text.parse_query(self.groups[0])
        self.kwdict["search_tags"] = \
            params.get("txtSearch") or params.get("txtsearch", "")
        return self._pagination(url, params)


class ComicartfansArtistExtractor(ComicartfansExtractor):
    subcategory = "artist"
    pattern = BASE_PATTERN + r"/comic-artists/([^/?#]+)\.asp"
    example = "https://www.comicartfans.com/comic-artists/ARTIST.asp"

    def works(self):
        artist = self.kwdict["search_tags"] = text.unquote(
            self.groups[0]).replace("_", " ")
        url = self.root + "/searchresult.asp"
        params = {"txtSearch": artist}
        return self._pagination(url, params)
