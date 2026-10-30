# -*- coding: utf-8 -*-

# Copyright 2026 Mike Fährmann
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for kokonotsuba imageboards"""

from .common import BaseExtractor, Message
from .. import text
import itertools


class KokonotsubaExtractor(BaseExtractor):
    """Base class for kokonotsuba extractors"""
    basecategory = "kokonotsuba"


BASE_PATTERN = KokonotsubaExtractor.update({
    "gurochan": {
        "root": None,
        "pattern": r"boards\.guro\.(?:st|cx)?",
    },
})


class KokonotsubaThreadExtractor(KokonotsubaExtractor):
    """Extractor for kokonotsuba threads"""
    subcategory = "thread"
    directory_fmt = ("{category}", "{board}", "{thread} {title}")
    filename_fmt = "{date} {file_name}.{extension}"
    archive_fmt = "{board}_{thread}_{file_id}"
    pattern = (BASE_PATTERN + r"/([^/?#]+)/"
               r"(?:uid:([^/?#]+)|koko\.php\?res=(\d+)(?:&page=(\d+))?)")
    example = "https://boards.guro.st/BOARD/koko.php?res=12345"

    def items(self):
        board = self.groups[-4]
        thread_uid = self.groups[-3]
        thread = self.groups[-2]
        pnum = text.parse_int(self.groups[-1], 1)

        if thread_uid is None:
            url = f"{self.root}/{board}/koko.php?res={thread}&page={pnum}"
            page = self.request(url).text
            thread_uid = text.extr(page, f'_{thread}" data-thread-uid="', '"')

        posts = self._pagination(board, thread_uid, pnum-1)
        op = next(posts)

        if thread is None:
            thread = op.get("parent_post_number")
        self.kwdict = {
            "board"     : board,
            "thread"    : thread,
            "thread_uid": thread_uid,
            "title"     : op.get("subject") or op.get("comment", "")[:50],
        }

        base = f"{self.root}/{board}/src/"
        needle = '<a href="' + base
        for post in itertools.chain((op,), posts):
            files = post.pop("attachments", ())
            post["date"] = self.parse_datetime_iso(post.get("timestamp"))
            post["count"] = len(files)
            yield Message.Directory, "", post

            if files:  # assume 1 file per post
                post.update(files[0])
                name = text.extr(post["html"], needle, '"')
                text.nameext_from_name(name, post)
                yield Message.Url, base + name, post

    def _pagination(self, board, thread, pnum=0):
        url = f"{self.root}/{board}/koko.php"
        params = {
            "mode"      : "module",
            "load"      : "postApi",
            "pageName"  : "thread",
            "thread_uid": thread,
            "page"      : pnum,
        }

        op = True
        while True:
            data = self.request_json(url, params=params)

            posts = data["posts"]
            if op:
                op = False
                yield posts[0]
            yield from posts[1:]

            if data["post_count"] <= 500:
                break
            params["page"] += 1


class KokonotsubaBoardExtractor(KokonotsubaExtractor):
    """Extractor for kokonotsuba boards"""
    subcategory = "board"
    pattern = BASE_PATTERN + r"/([^/?#]+)(?:/(?:koko\.php\?page=)?(\d+))?"
    example = "https://boards.guro.st/art/"

    def items(self):
        board = self.groups[-2]
        pnum = text.parse_int(self.groups[-1], 1)
        base = f"{self.root}/{board}/uid:"
        for thread in self._pagination(board, pnum-1):
            thread["_extractor"] = KokonotsubaThreadExtractor
            yield Message.Queue, base + thread['thread_uid'], thread

    def _pagination(self, board, pnum=0):
        url = f"{self.root}/{board}/koko.php"
        params = {
            "mode"    : "module",
            "load"    : "postApi",
            "pageName": "threads",
            "page"    : pnum,
        }

        while True:
            data = self.request_json(url, params=params)

            yield from data["threads"]

            if data["thread_count"] < data["threads_per_page"]:
                break
            params["page"] += 1
