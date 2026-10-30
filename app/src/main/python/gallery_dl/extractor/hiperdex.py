# -*- coding: utf-8 -*-

# Copyright 2020-2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://hiperdex.com/"""

from .common import ChapterExtractor, MangaExtractor
from .. import text, util

BASE_PATTERN = (r"((?:https?://)?(?:www\.)?"
                r"(?:1st)?hiper(?:dex|toon)\d?\.(?:com|net|info|top))")


class HiperdexBase():
    """Base class for hiperdex extractors"""
    category = "hiperdex"
    root = "https://hiperdex.com"

    def manga_data(self, slug):
        manga = self.request_api("series.bySlugWithGenres", {"slug": slug})
        manga["manga"] = manga.pop("title", None)
        manga["manga_id"] = manga.pop("id", None)
        manga["manga_slug"] = manga.pop("slug", None)
        manga["manga_cover"] = manga.pop("coverUrl", None)
        manga["description"] = manga.pop("synopsis", None)
        manga["author"] = manga.pop("authors", None)
        manga["artist"] = manga.pop("artists", None)
        manga["tags"] = manga.pop("genres", None)
        manga["manga_date"] = self.parse_datetime_iso(
            manga.pop("createdAt", None))
        manga["manga_date_updated"] = self.parse_datetime_iso(
            manga.pop("updatedAt", None))

        return manga

    def chapter_data(self, chapter):
        if chapter.startswith("chapter-"):
            chapter = chapter[8:]
        chapter, _, minor = chapter.partition("-")
        return {
            **self.cache(self.manga_data, self.manga.lower()),
            "chapter"      : text.parse_int(chapter),
            "chapter_minor": "." + minor if minor and minor != "end" else "",
        }

    def request_api(self, endpoint, params):
        url = "https://hiperdex.com/api/trpc/" + endpoint
        params = {"input": util.json_dumps({"json": params})}
        headers = {"x-cfg-auth": "yceqt7qgu004"}

        result = self.request_json(url, params=params, headers=headers)
        return result["result"]["data"]["json"]


class HiperdexChapterExtractor(HiperdexBase, ChapterExtractor):
    """Extractor for hiperdex manga chapters"""
    pattern = BASE_PATTERN + r"(/mangas?/([^/?#]+)/([^/?#]+))"
    example = "https://hiperdex.com/manga/MANGA/CHAPTER/"

    def __init__(self, match):
        root, path, self.manga, self.chapter = match.groups()
        self.root = text.ensure_http_scheme(root)
        ChapterExtractor.__init__(self, match, self.root + path)

    def metadata(self, _):
        return self.chapter_data(self.chapter)

    def images(self, _):
        pages = self.request_api("reader.chapterPages", {
            "seriesSlug": self.manga,
            "chapterNumber": float(self.chapter),
        })
        pages.sort(key=lambda x: x.get("pageOrder"))
        return [(page["webpUrl"], page) for page in pages]


class HiperdexMangaExtractor(HiperdexBase, MangaExtractor):
    """Extractor for hiperdex manga"""
    chapterclass = HiperdexChapterExtractor
    pattern = BASE_PATTERN + r"(/mangas?/([^/?#]+))/?$"
    example = "https://hiperdex.com/manga/MANGA/"

    def __init__(self, match):
        root, path, self.manga = match.groups()
        self.root = text.ensure_http_scheme(root)
        MangaExtractor.__init__(self, match, self.root + path)

    def chapters(self, page):
        manga = self.cache(self.manga_data, self.manga)
        base = f"{self.root}/manga/{manga['manga_slug']}/"
        chapters = self.request_api("series.chapters", {
            "seriesId": manga["manga_id"],
        })

        results = []
        for ch in chapters:
            number = str(ch["number"])
            results.append((base + number, {
                "date": self.parse_datetime_iso(
                    ch.pop("createdAt", None)),
                "date_updated": self.parse_datetime_iso(
                    ch.pop("updatedAt", None)),
                **self.chapter_data(number),
                **ch,
            }))
        return results


class HiperdexArtistExtractor(HiperdexBase, MangaExtractor):
    """Extractor for an artists's manga on hiperdex"""
    subcategory = "artist"
    categorytransfer = False
    chapterclass = HiperdexMangaExtractor
    reverse = False
    pattern = BASE_PATTERN + r"(/manga-a(?:rtist|uthor)/(?:[^/?#]+))"
    example = "https://hiperdex.com/manga-artist/NAME/"

    def __init__(self, match):
        self.root = text.ensure_http_scheme(match[1])
        MangaExtractor.__init__(self, match, self.root + match[2] + "/")

    def chapters(self, page):
        results = []
        for info in text.extract_iter(page, 'id="manga-item-', '<img'):
            url = text.extr(info, 'href="', '"')
            results.append((url, {}))
        return results
