"""Audible metadata resolver for Sleeve — Libation-derived, in-scope slice.

Uses Audible's PUBLIC catalog API (api.audible.com, no account, no auth)
for metadata only: title, authors, narrator, length. Audio is never
fetched from Audible (those streams are DRM-protected — out of scope,
hard boundary); the track is matched on YouTube via the ranked pipeline
like every other metadata source.

Public surface (JSON strings, like sleeve_engine):
    resolve(url) -> '{"ok": true, "title": ..., "tracks":
                     [{"title", "artist", "album", "durationMs"}]}'
    search(keywords) -> same shape

urllib only (Chaquopy-safe).
"""

import json
import re
import urllib.parse
import urllib.request

_UA = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
)
_ASIN_RE = re.compile(r"\b([AB][0-9A-Z]{9})\b")
_TIMEOUT = 15


def _api(url):
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        return json.loads(resp.read().decode())


def _product_dto(product):
    authors = ", ".join(
        a.get("name", "") for a in (product.get("authors") or [])
    )
    narrators = ", ".join(
        a.get("name", "") for a in (product.get("narrators") or [])
    )
    artist = authors + (" (narrated by " + narrators + ")" if narrators else "")
    minutes = (product.get("runtime_length_min") or 0)
    return {
        "title": product.get("title") or "",
        "artist": artist,
        "album": "Audible",
        "durationMs": int(minutes) * 60 * 1000,
    }


def resolve(url, keywords=""):
    """Resolve an Audible product URL/ASIN, or a keyword search."""
    try:
        asin = None
        m = _ASIN_RE.search(url or "")
        if m:
            asin = m.group(1)
        if asin:
            product = _api(
                "https://api.audible.com/1.0/catalog/products/"
                + asin
                + "?response_groups=product_attrs,contributors,product_desc"
            )
            dto = _product_dto(product.get("product") or {})
            return json.dumps(
                {"ok": True, "kind": "book", "title": dto["title"], "tracks": [dto]}
            )
        query = keywords or urllib.parse.unquote(url or "")
        if not query.strip():
            return json.dumps({"ok": False, "error": "no asin or keywords"})
        data = _api(
            "https://api.audible.com/1.0/catalog/products?keywords="
            + urllib.parse.quote(query)
            + "&num_results=1&response_groups=product_attrs,contributors,product_desc"
        )
        products = data.get("products") or []
        if not products:
            return json.dumps({"ok": False, "error": "no audible results"})
        dto = _product_dto(products[0])
        return json.dumps(
            {"ok": True, "kind": "book", "title": dto["title"], "tracks": [dto]}
        )
    except Exception as e:  # noqa: BLE001 — bridge contract: failures are JSON
        return json.dumps({"ok": False, "error": str(e)})
