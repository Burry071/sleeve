# -*- coding: utf-8 -*-
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://framedsc.com/"""

from .common import Extractor, Message
from .. import text

BASE_PATTERN = r"(?:https?://)?(?:cdn\.|www\.)?framedsc.com"
RELATIONS = "<=>"


class FramedscExtractor(Extractor):
    """Base class for framedsc extractors"""
    category = "framedsc"
    archive_fmt = "{filename}"

    def _load_db(self):
        base = ("https://raw.githubusercontent.com"
                "/originalnicodrgitbot/hall-of-framed-db/main/")
        return (
            self.request_json(base + "shotsdb.json")["_default"],
            self.request_json(base + "authorsdb.json")["_default"],
        )

    def items(self):
        self.image_db, self.author_db = self.cache(self._load_db, _key=None)

        for image in self.images():
            url = image["shotUrl"]
            image = text.nameext_from_url(url, image.copy())
            image["date"] = self.parse_datetime_iso(image["date"])
            yield Message.Directory, "", image
            yield Message.Url, url, image


class FramedscImageExtractor(FramedscExtractor):
    """Extractor for single framedsc image"""
    subcategory = "image"
    archive_fmt = "{filename}"
    pattern = BASE_PATTERN + r"/HallOfFramed/?\?[^#]*\bimageId=(\d+)"
    example = "https://framedsc.com/HallOfFramed/?imageId=12345"

    def images(self):
        image_id = int(self.groups[0])
        for image in self.image_db.values():
            if image["epochTime"] == image_id:
                return (image,)
        return ()


class FramedscRawExtractor(FramedscExtractor):
    """Extractor for single framedsc image (raw image link)"""
    subcategory = "raw"
    pattern = BASE_PATTERN + r"/images(/[^/?#]+)"
    example = "https://cdn.framedsc.com/images/12345_NAME.EXT"

    def images(self):
        filename = self.groups[0]
        for image in self.image_db.values():
            if image["shotUrl"].endswith(filename):
                return (image,)
        return ()


class FramedscSearchExtractor(FramedscExtractor):
    """Extractor for framedsc image searches"""
    subcategory = "search"
    pattern = BASE_PATTERN + r"/HallOfFramed/?\?(?![^#]*\bimageId=)([^#]*)"
    example = "https://framedsc.com/HallOfFramed/?author=AUTHOR&title=TITLE"

    def clean_filters(self, unclean_filters):
        def valid_date(date):
            return bool(self.parse_datetime_iso(date))

        def valid_int(integer):
            try:
                int(integer)
                return True
            except (ValueError):
                return False

        filters = {
            "author": [],
            "title": [],
            "on": [],
            "before": [],
            "after": [],
            "color": [],
            "width": {
                ">": [],
                "<": [],
                "=": []
            },
            "height": {
                ">": [],
                "<": [],
                "=": []
            },
            "score": {
                ">": [],
                "<": [],
                "=": []
            },
        }

        for (field, value) in unclean_filters.items():
            value = text.unquote(value)

            if field == "author":
                filters[field].append(self.find_author(value))
            elif field == "title":
                filters[field].append(value.replace("+", " "))
            elif field in {"width", "height", "score"}:
                if not value or value[0] not in "<>":
                    filters[field]["="].append(value[1:])
                else:
                    filters[field][value[0]].append(value[1:])
            else:
                if field in filters.keys():
                    filters[field].append(value)

        for field in ("on", "before", "after"):
            if filters[field]:
                filters[field] = [
                    self.parse_datetime_iso(date)
                    for date in filters[field]
                    if valid_date(date)
                ]
                if not filters[field]:
                    return

        for field in ("width", "height", "score"):
            field_exists = False
            for relation in RELATIONS:
                field_exists = len(filters[field][relation]) > 0
                filters[field][relation] = [
                    int(integer)
                    for integer in filters[field][relation]
                    if valid_int(integer)
                ]

            if field_exists and all(
                not filters[field][relation]
                for relation in RELATIONS
            ):
                return

        return filters

    def find_author(self, author_nick):
        for author in self.author_db.values():
            if author["authorNick"] == author_nick:
                return author["authorid"]
        raise self.exc.NotFoundError("author")

    def images(self):
        unclean_filters = text.parse_query(self.groups[0])
        filters = self.clean_filters(unclean_filters)

        if not filters:
            return self.image_db.values()

        def any_match_strict(image_field, target_fields):
            return any(
                [image_field == target_field for target_field in target_fields]
            )

        def any_greater_strict(image_field, target_fields):
            return any(
                [image_field >= target_field for target_field in target_fields]
            )

        def any_less_strict(image_field, target_fields):
            return any(
                [image_field <= target_field for target_field in target_fields]
            )

        def any_match(image_field, target_fields):
            return not target_fields or any_match_strict(
                image_field, target_fields)

        def any_greater(image_field, target_fields):
            return not target_fields or any_greater_strict(
                image_field, target_fields)

        def any_less(image_field, target_fields):
            return not target_fields or any_less_strict(
                image_field, target_fields)

        def integer_field_check(image, field):
            filter_dne = all([len(filters[field][relation]) ==
                             0 for relation in RELATIONS])

            equal = any_match_strict(image[field], filters[field]["="])
            greater = any_greater_strict(image[field], filters[field][">"])
            less = any_less_strict(image[field], filters[field]["<"])

            return filter_dne or equal or greater or less

        def title_check(title, target_titles):
            return not target_titles or any(
                target_title in title
                for target_title in target_titles
            )

        for image in self.image_db.values():
            author = any_match(image["author"], filters["author"])
            title = title_check(image["gameName"], filters["title"])
            color = any_match(image["colorName"], filters["color"])

            date = self.parse_datetime_iso(image["date"][:10])
            before = any_less(date, filters["before"])
            on = any_match(date, filters["on"])
            after = any_greater(date, filters["after"])

            width = integer_field_check(image, "width")
            height = integer_field_check(image, "height")
            score = integer_field_check(image, "score")

            if author and title and color and before and on and after and \
                    width and height and score:
                yield image
