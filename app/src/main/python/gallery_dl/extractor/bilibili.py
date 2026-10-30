# -*- coding: utf-8 -*-

# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License version 2 as
# published by the Free Software Foundation.

"""Extractors for https://www.bilibili.com/"""

from .common import Extractor, Message
from .. import text, util


class BilibiliExtractor(Extractor):
    """Base class for bilibili extractors"""
    category = "bilibili"
    root = "https://www.bilibili.com"
    request_interval = (3.0, 6.0)

    def _init(self):
        self.api = BilibiliAPI(self)

    def items(self):
        for article in self.articles():
            article["_extractor"] = BilibiliArticleExtractor
            url = f"{self.root}/opus/{article['opus_id']}"
            yield Message.Queue, url, article

    def articles(self):
        return ()


class BilibiliArticleExtractor(BilibiliExtractor):
    """Extractor for a bilibili article"""
    subcategory = "article"
    pattern = (r"(?:https?://)?"
               r"(?:t\.bilibili\.com|(?:www\.)?bilibili.com/opus)/(\d+)")
    example = "https://www.bilibili.com/opus/12345"
    directory_fmt = ("{category}", "{username}")
    filename_fmt = "{id}_{num}{suffix}.{extension}"
    archive_fmt = "{id}_{num}{suffix}"

    def items(self):
        article_id = self.groups[0]
        article = self.api.article(article_id)

        # Flatten modules list
        pics = []
        modules = {}
        article["title"] = ""
        article["content"] = txt = []
        for module in article["detail"]["modules"]:
            if m := module.get("module_author"):
                article["username"] = m.get("name")
                article["user_id"] = m.get("mid")
                article["date"] = self.parse_timestamp(m.get("pub_ts"))
            if m := module.get("module_title"):
                article["title"] = m.get("text")
                article["tags"] = m.get("tags")
            if m := module.get("module_topic"):
                article["topic"] = m.get("name")
                article["topic_id"] = m.get("id")
                article["topic_url"] = m.get("jump_url")
            if m := module.get("module_blocked"):
                self.log.warning("%s: Blocked Article\n%s", article_id,
                                 m.get("hint_message"))
            if m := module.get("module_top"):
                try:
                    pics.extend(m["display"]["album"]["pics"])
                except Exception:
                    pass
            if m := module.get("module_content"):
                for paragraph in m["paragraphs"]:
                    if "pic" in paragraph:
                        try:
                            pics.extend(paragraph["pic"]["pics"])
                        except Exception:
                            pass
                    if "text" in paragraph:
                        try:
                            for node in paragraph["text"]["nodes"]:
                                if n := node.get("word"):
                                    txt.append(n["words"])
                                if n := node.get("rich"):
                                    txt.append(n.get("orig_text") or n["text"])
                        except Exception:
                            pass
            del module["module_type"]
            modules.update(module)

        article["detail"]["modules"] = modules
        article["count"] = len(pics)
        yield Message.Directory, "", article

        livephoto = self.config("livephoto", True)
        article["suffix"] = ""
        for article["num"], pic in enumerate(pics, 1):
            url = pic["url"]
            article.update(pic)
            yield Message.Url, url, text.nameext_from_url(url, article)

            if livephoto and (url := pic.get("live_url")):
                article["suffix"] = "l"
                yield Message.Url, url, text.nameext_from_url(url, article)
                article["suffix"] = ""


class BilibiliUserArticlesExtractor(BilibiliExtractor):
    """Extractor for a bilibili user's articles"""
    subcategory = "user-articles"
    pattern = (r"(?:https?://)?space\.bilibili\.com/(\d+)"
               r"/(?:article|upload/opus|dynamic)")
    example = "https://space.bilibili.com/12345/article"

    def articles(self):
        return self.api.user_articles(self.groups[0])


class BilibiliUserArticlesFavoriteExtractor(BilibiliExtractor):
    subcategory = "user-articles-favorite"
    pattern = (r"(?:https?://)?space\.bilibili\.com"
               r"/(\d+)/favlist\?fid=opus")
    example = "https://space.bilibili.com/12345/favlist?fid=opus"
    _warning = True

    def articles(self):
        if self._warning:
            if not self.cookies_check(("SESSDATA",)):
                self.log.error("'SESSDATA' cookie required")
            BilibiliUserArticlesFavoriteExtractor._warning = False
        return self.api.user_favlist()


class BilibiliAPI():
    def __init__(self, extractor):
        self.extractor = extractor
        self.exc = extractor.exc

    def _call(self, endpoint, params):
        url = "https://api.bilibili.com/x/polymer/web-dynamic/v1" + endpoint
        data = self.extractor.request_json(url, params=params)

        if data["code"]:
            self.extractor.log.debug("Server response: %s", data)
            raise self.exc.AbortExtraction("API request failed")

        return data

    def user_articles(self, user_id):
        endpoint = "/opus/feed/space"
        params = {"host_mid": user_id}

        while True:
            data = self._call(endpoint, params)

            for item in data["data"]["items"]:
                params["offset"] = item["opus_id"]
                yield item

            if not data["data"]["has_more"]:
                break

    def article(self, article_id):
        url = "https://www.bilibili.com/opus/" + article_id

        while True:
            page = self.extractor.request(url).text
            try:
                return util.json_loads(text.extr(
                    page, "window.__INITIAL_STATE__=", "};") + "}")
            except Exception:
                if "window._riskdata_" not in page:
                    raise self.exc.AbortExtraction(
                        article_id + ": Unable to extract INITIAL_STATE data")
            self.extractor.wait(seconds=300)

    def user_favlist(self):
        endpoint = "/opus/feed/fav"
        params = {"page": 1, "page_size": 20}

        while True:
            data = self._call(endpoint, params)["data"]

            yield from data["items"]

            if not data.get("has_more"):
                break
            params["page"] += 1

    def login_user_id(self):
        url = "https://api.bilibili.com/x/space/v2/myinfo"
        data = self.extractor.request_json(url)

        if data["code"] != 0:
            self.extractor.log.debug("Server response: %s", data)
            raise self.exc.AbortExtraction(
                "API request failed. Are you logges in?")
        try:
            return data["data"]["profile"]["mid"]
        except Exception:
            raise self.exc.AbortExtraction("API request failed")
