# -*- coding: utf-8 -*-

# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://ganknow.com/"""

from .common import Extractor, Message
from .. import text

BASE_PATTERN = r"(?:https?://)?(?:www\.)?ganknow\.com"


class GanknowExtractor(Extractor):
    """Base class for ganknow extractors"""
    category = "ganknow"
    root = "https://ganknow.com"
    directory_fmt = ("{category}", "{user[nickname]}", "{post_id} {title}")
    filename_fmt = "{num:>02}_{media_id}.{extension}"
    archive_fmt = "{post_id}_{media_id}"
    cookies_domain = ".ganknow.com"
    request_interval = (0.5, 1.5)

    def _init(self):
        self.api = GankAPI(self)
        self.previews = self.config("previews", True)

    def items(self):
        for post in self.posts():
            post = self._prepare_post(post)
            files = self._extract_files(post)
            post["count"] = len(files)

            yield Message.Directory, "", post
            for post["num"], file in enumerate(files, 1):
                url = file["url"]
                post.update(file)
                text.nameext_from_url(url, post)
                if not post["extension"]:
                    post["filename"] = post["media_id"]
                    post["extension"] = \
                        "mp4" if post["type"] == "video" else "jpg"
                yield Message.Url, url, post

    def _prepare_post(self, post):
        post["post_id"] = post["id"]
        post["post_url"] = f"{self.root}/post/{post['id']}"
        post["date"] = self.parse_datetime_iso(post["createdAt"])
        post["date_updated"] = self.parse_datetime_iso(post["updatedAt"])
        post["tags"] = [tag["name"] for tag in post.get("postTags") or ()]
        post["links"] = text.extract_urls(post.get("content") or "")
        return post

    def _extract_files(self, post):
        files = []

        for media in sorted(
                post.get("postMedia") or (),
                key=lambda media: media.get("sort") or 0):

            url = media.get("url") or media.get("thumbUrl")
            preview = False

            if not url:
                if self.previews:
                    url = media.get("previewUrl") or media.get("blurUrl")
                    preview = True
                    self._warn_preview()
                if not url:
                    self.log.warning("%s: No URL for media %s",
                                     post["id"], media["id"])
                    continue
            elif media.get("type") == "image" and \
                    ".googleusercontent.com/" in url:
                url += "=s0"

            files.append({
                "url"          : url,
                "type"         : media["type"],
                "media"        : media,
                "media_id"     : media["id"],
                "media_url"    : media.get("url") or "",
                "media_thumb"  : media.get("thumbUrl") or "",
                "media_blur"   : media.get("blurUrl") or "",
                "media_preview": media.get("previewUrl") or "",
                "preview"      : preview,
            })

        return files

    def _warn_preview(self):
        from .. import util
        GanknowExtractor._warn_preview = util.noop
        self.log.warning("Downloading blurred previews. Use cookies "
                         "or disable 'previews' to skip unavailable media.")


class GanknowPostExtractor(GanknowExtractor):
    """Extractor for a single ganknow post"""
    subcategory = "post"
    pattern = BASE_PATTERN + r"/post/([0-9a-f-]+)"
    example = "https://ganknow.com/post/01234567-89ab-cdef-0123-456789abcdef"

    def posts(self):
        post = self.api.post(self.groups[0])
        post["user"] = post.get("authorUser") or {}
        return (post,)


class GanknowUserExtractor(GanknowExtractor):
    """Extractor for a ganknow user's posts"""
    subcategory = "user"
    pattern = BASE_PATTERN + r"/(?!post/)(?:u/)?([^/?#]+)(?:\?([^#]+))?"
    example = "https://ganknow.com/USER"

    def posts(self):
        user = self.kwdict["user"] = \
            self.api.user(text.unquote(self.groups[0]))
        return self.api.posts(user["id"], self.groups[1])


class GankAPI():
    ROOT = "https://api.ganknow.com/v1"

    def __init__(self, extractor):
        self.extractor = extractor
        self.headers = {
            "Accept": "application/json, text/plain, */*",
        }

        token = extractor.config("token")
        if not token:
            for cookie in extractor.cookies:
                if cookie.name == "auth._token.local":
                    token = cookie.value
                    break
            else:
                return

        token = text.unquote(token)
        if not token.startswith("Bearer "):
            token = "Bearer " + token
        self.headers["Authorization"] = token

    def user(self, username):
        endpoint = "/users/nickname/" + username
        return self._call(endpoint, notfound="user")["data"]

    def post(self, post_id):
        endpoint = "/posts/" + post_id
        return self._call(endpoint, notfound="post")["data"]

    def posts(self, user_id, query=None):
        params = text.parse_query(query)
        params.pop("tab", None)
        params["author"] = user_id
        params.setdefault("limit", 50)
        params["page"] = text.parse_int(params.get("page"), 1)

        return self._pagination("/posts", params)

    def pinned_posts(self, user_id):
        endpoint = "/posts/pinned-post/" + user_id
        return self._call(endpoint)["data"]

    def _call(self, endpoint, params=None, **kwargs):
        kwargs["params"] = params
        kwargs["headers"] = self.headers
        return self.extractor.request_json(self.ROOT + endpoint, **kwargs)

    def _pagination(self, endpoint, params):
        limit = text.parse_int(params.get("limit"), 50)

        while True:
            posts = self._call(endpoint, params)["data"]
            if not posts:
                break

            yield from posts

            if len(posts) < limit:
                break
            params["page"] += 1
