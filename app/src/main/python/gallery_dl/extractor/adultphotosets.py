# -*- coding: utf-8 -*-

# Copyright 2026 w43322

# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://adultphotosets.best/"""

from .common import Extractor, Message
from .. import text

BASE_PATTERN = r"(?:https?://)?(?:www\.)?adultphotosets\.best"
PHOTO_PATTERN = (
    r"(amateur-photo|hentai-pictures|"
    r"(?:alternative|artistic|softcore|hardcore|lesbian)-photo-sets)")


class AdultphotosetsExtractor(Extractor):
    """Base class for adultphotosets extractors"""
    category = "adultphotosets"
    root = "https://adultphotosets.best"
    request_interval = (0.5, 1.5)

    def items(self):
        data = {"_extractor": AdultphotosetsGalleryExtractor}
        for url in self.galleries():
            yield Message.Queue, url, data

    def _pagination(self, url):
        last = None

        while True:
            page = self.request(url).text

            for match in AdultphotosetsGalleryExtractor.pattern.finditer(page):
                if (url := match[0]) != last:
                    yield (last := url)

            nav = text.extr(page, '<span class="page_next"', '</span>')
            url = text.extr(nav, '<a href="', '"')
            if not url:
                break
            url = text.unescape(url)


class AdultphotosetsGalleryExtractor(AdultphotosetsExtractor):
    """Extractor for adultphotosets image galleries"""
    subcategory = "gallery"
    parent = True
    directory_fmt = ("{category}", "{title} ({gallery_id})")
    pattern = rf"{BASE_PATTERN}/(?:{PHOTO_PATTERN}/)?(\d+)-([^/?#]+)\.html"
    example = "https://adultphotosets.best/hardcore-photo-sets/123-TITLE.html"

    def items(self):
        page = self.request(self.url).text
        article = text.extr(page, '<article', '</article>')
        images = self.images(article)
        data = self.metadata(page, article)
        data["count"] = len(images)

        yield Message.Directory, "", data
        for data["num"], url in enumerate(images, 1):
            yield Message.Queue, text.unescape(url), data

    def metadata(self, page, article):
        section, gallery_id, slug = self.groups
        return {
            "gallery_id": text.parse_int(gallery_id),
            "slug"      : slug,
            "section"   : section or "",
            "title"     : text.unescape(text.extr(
                article, '<h2 class="title">', '</h2>')).strip(),
            "date"      : self.parse_datetime_iso(text.extr(
                article, '<time datetime="', '"')),
            "uploader"  : text.unescape(text.extr(
                article, "onclick=\"ShowProfile('", "'")),
            "tags"      : text.split_html(text.extr(
                page, '<div class="tag_list">', '</div>')),
            "likes"     : text.parse_int(text.extr(
                article, '<span id="ratig-layer-', '</').rpartition(">")[2]),
            "views"     : text.parse_int(text.extr(
                article, 'title="Views: ', '"')),
            "comments"  : text.parse_int(text.extr(
                article, 'title="Comments: ', '"')),
        }

    def images(self, article):
        if content := text.extr(article, '<!--QuoteEEnd--><br><br>', "\n"):
            return list(text.extract_iter(content, ' href="', '"'))

        # legacy galleries
        content = text.extr(
            article, '<div class="text">', '<div class="story_tools')
        return [href for href in text.extract_iter(content, ' href="', '"')
                if not href.startswith(self.root)]


class AdultphotosetsTagExtractor(AdultphotosetsExtractor):
    """Extractor for adultphotosets tag searches"""
    subcategory = "tag"
    pattern = BASE_PATTERN + r"(/tags/([^/?#]+)(?:/page/\d+)?)"
    example = "https://adultphotosets.best/tags/TAG/"

    def galleries(self):
        path, tag = self.groups
        self.kwdict["search_tags"] = text.unquote(tag)
        return self._pagination(f"{self.root}{path}/")


class AdultphotosetsCategoryExtractor(AdultphotosetsExtractor):
    """Extractor for adultphotosets image categories"""
    subcategory = "category"
    pattern = rf"{BASE_PATTERN}(/{PHOTO_PATTERN}(?:/page/\d+)?)/?$"
    example = "https://adultphotosets.best/softcore-photo-sets/"

    def galleries(self):
        path, self.kwdict["section"] = self.groups
        return self._pagination(f"{self.root}{path}/")


class AdultphotosetsHomeExtractor(AdultphotosetsExtractor):
    """Extractor for the adultphotosets home listing"""
    subcategory = "home"
    pattern = BASE_PATTERN + r"(/page/\d+)?/?$"
    example = "https://adultphotosets.best/"

    def galleries(self):
        return self._pagination(f"{self.root}{self.groups[0] or ''}/")
