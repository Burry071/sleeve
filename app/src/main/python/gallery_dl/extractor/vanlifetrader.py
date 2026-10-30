# -*- coding: utf-8 -*-

# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://vanlifetrader.com/"""

from .common import Extractor, Message
from .. import text

BASE_PATTERN = r"(?:https?://)?(?:www\.)?vanlifetrader\.com"


class VanlifetraderExtractor(Extractor):
    """Base class for vanlifetrader extractors"""
    category = "vanlifetrader"
    root = "https://vanlifetrader.com"

    def _api_listing_metadata(self, listing):
        title = (listing.get("yoast_head_json") or {}).get("og_title", "")
        if title.endswith(" - Vanlife Trader"):
            title = title[:-17]

        return {
            "listing_id": listing["id"],
            "slug"      : listing["slug"],
            "title"     : title,
            "date"      : self.parse_datetime_iso(listing["date"]),
        }

    def _api_listing_images(self, listing_id):
        url = f"{self.root}/wp-json/wp/v2/media"
        params = {
            "parent"  : listing_id,
            "per_page": 100,
            "_fields" : "id,source_url",
        }
        return self.request_json(url, params=params)

    def _api_listings(self, params=None):
        url = f"{self.root}/wp-json/wp/v2/listing"
        p = {
            "per_page": 100,
            "page"    : 1,
            "_fields" : "id,slug,link,date,yoast_head_json",
        }
        if params:
            p.update(params)

        while True:
            listings = self.request_json(url, params=p)
            if not listings:
                return
            yield from listings
            if len(listings) < p["per_page"]:
                return
            p["page"] += 1


class VanlifetraderListingExtractor(VanlifetraderExtractor):
    """Extractor for individual van listings on vanlifetrader.com"""
    subcategory = "listing"
    directory_fmt = ("{category}", "{title}")
    filename_fmt = "{listing_id}_{num:>02}.{extension}"
    archive_fmt = "{listing_id}_{num}"
    pattern = BASE_PATTERN + r"/listing/([^/?#]+)"
    example = "https://vanlifetrader.com/listing/YEAR-MAKE-MODEL-HASH/"

    def items(self):
        url = f"{self.root}/wp-json/wp/v2/listing"
        params = {
            "slug"   : self.groups[0],
            "_fields": "id,slug,link,date,yoast_head_json",
        }
        listings = self.request_json(url, params=params)

        if not listings:
            raise self.exc.NotFoundError("listing")

        data = self._api_listing_metadata(listings[0])
        yield Message.Directory, "", data

        for num, media in enumerate(
                self._api_listing_images(listings[0]["id"]), 1):
            image_url = media["source_url"]
            image_data = text.nameext_from_url(image_url, dict(data))
            image_data["num"] = num
            image_data["media_id"] = media["id"]
            yield Message.Url, image_url, image_data


class VanlifetraderExploreExtractor(VanlifetraderExtractor):
    """Extractor for explore and search pages on vanlifetrader.com"""
    subcategory = "explore"
    pattern = BASE_PATTERN + r"/explore"
    example = "https://vanlifetrader.com/explore/"

    def items(self):
        data = {"_extractor": VanlifetraderListingExtractor}
        for listing in self._api_listings():
            yield Message.Queue, listing["link"], data
