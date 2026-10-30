# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://clonr.co/"""

from .common import Extractor, Message
from .. import text


class ClonrFolderExtractor(Extractor):
    category = "clonr"
    subcategory = "folder"
    root = "https://clonr.co"
    directory_fmt = ("{category}", "{name} ({id})")
    filename_fmt = "{num:?/ />02}{filename}.{extension}"
    archive_fmt = "{id}_{num}"
    pattern = r"(?:https?://)?(?:www\.)?clonr\.co/([^/?#]+)"
    example = "https://clonr.co/ID"

    def items(self):
        url = f"{self.root}/api/clone/{self.groups[0]}"
        data = self.request_json(url)

        files = data.pop("files")
        data["count"] = len(files)
        yield Message.Directory, "", data

        if self.config("zip", False):
            data["num"] = 0
            url = data["zip_url"]
            yield Message.Url, url, text.nameext_from_url(url, data)
        else:
            for data["num"], file in enumerate(files, 1):
                data.update(file)
                url = file["url"]
                text.nameext_from_name(data["name"], data)
                if not data["extension"]:
                    data["extension"] = text.ext_from_url(url)
                yield Message.Url, url, data
