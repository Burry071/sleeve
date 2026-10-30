# -*- coding: utf-8 -*-

# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://mangayi.com/"""

from .common import ChapterExtractor, MangaExtractor
from .. import text, util

BASE_PATTERN = r"(?:https?://)?(?:www\.)?mangayi\.com"


class MangayiBase():
    """Base class for mangayi.com extractors"""
    category = "mangayi"
    root = "https://mangayi.com"


class MangayiChapterExtractor(MangayiBase, ChapterExtractor):
    """Extractor for manga-chapters from mangayi.com"""
    pattern = BASE_PATTERN + r"(/read/[^/?#]+/(?:v(\d+)/)?chapter/\d+[^/?#]*)"
    example = "https://mangayi.com/read/TITLE/chapter/1/"

    def metadata(self, page):
        data = util.json_loads("{" + text.extr(
            page, '","@type":"ComicIssue",', '</script>'))
        chapter, dot, minor = data["issueNumber"].partition(".")

        return {
            "manga"        : data["isPartOf"]["name"],
            "title"        : data["name"].partition(":")[2].strip(),
            "volume"       : text.parse_int(self.groups[1]),
            "chapter"      : text.parse_int(chapter),
            "chapter_minor": dot + minor,
            "date"         : self.parse_datetime_iso(data["datePublished"]),
            "lang"         : data["inLanguage"],
        }

    def images(self, page):
        data = util.json_loads("{" + text.extr(
            page, '","@type":"MediaGallery",', '</script>'))

        return [
            (text.ensure_http_scheme(image["url"]), None)
            for image in data["associatedMedia"]
        ]


class MangayiMangaExtractor(MangayiBase, MangaExtractor):
    chapterclass = MangayiChapterExtractor
    pattern = BASE_PATTERN + r"(/read/[^/?#]+)"
    example = "https://mangayi.com/read/TITLE/"

    def chapters(self, page):
        manga = util.json_loads("{" + text.extr(
            page, '","@type":"ComicSeries",', '</script>'))["name"]
        data = util.json_loads("{" + text.extr(
            page, '","@type":"ItemList",', '</script>'))

        results = []
        for ch in data["itemListElement"]:
            url = ch["url"]
            chapter = url[url.rfind("/", 0, -2)+1:-1]
            chapter, sep, minor = chapter.partition("-")

            results.append((url, {
                "manga"        : manga,
                "chapter"      : text.parse_int(chapter),
                "chapter_minor": sep + minor,
                "title"        : ch["name"].partition(":")[2].strip(),
                "lang"         : "en",
            }))
        return results
