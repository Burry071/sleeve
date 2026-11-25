"""Audible metadata resolver for Sleeve — Libation-derived, in-scope slice.

Uses Audible's PUBLIC catalog API (api.audible.com, no account, no auth)
for metadata only: title, authors, narrator, length. Audio is never
fetched from Audible (those streams are DRM-protected — out of scope,
hard boundary); the track is matched on YouTube via the ranked pipeline
like every other metadata source.

Public surface (JSON strings, like sleeve_engine):
    resolve(url) -> '{"ok": true, "title": ..., "tracks":
                     [{"title", "artist", "authors", "durationMs",
                       "language"}]}'
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
_ASIN_RE = re.compile(r"\b([0-9A-Z]{10})\b")
# Audible product URLs are /pd/<ASIN> or /pd/<slug>/<ASIN>. Read the path
# segment first: ASINs are not always letter-prefixed (1705047572 is a real
# Fellowship of the Ring ASIN), and a bare token scan could hit a slug word.
_PATH_ASIN_RE = re.compile(r"/pd/(?:[^/?#]+/)?([0-9A-Z]{10})(?=[/?#]|$)")
_TIMEOUT = 15
_REGIONAL_TLDS = frozenset({
    "com",
    "co.uk",
    "de",
    "fr",
    "co.jp",
    "com.au",
    "ca",
    "it",
    "es",
    "in",
    "com.br",
})


def _api_host(url):
    """Map regional Audible storefront hosts to their catalog API host."""
    text = (url or "").strip()
    if not text:
        return "api.audible.com"
    parsed = urllib.parse.urlsplit(text)
    host = (parsed.hostname or "").lower().rstrip(".")
    if not host and "audible." in text.lower():
        parsed = urllib.parse.urlsplit("https://" + text)
        host = (parsed.hostname or "").lower().rstrip(".")
    for tld in _REGIONAL_TLDS:
        suffix = "audible." + tld
        if host == suffix or host.endswith("." + suffix):
            return "api.audible." + tld
    return "api.audible.com"


def _api(url):
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
        return json.loads(resp.read().decode())


def _language(value):
    """One clean language token: "English (US)" / "en-US" -> "english" / "en".

    The gate maps names and codes alike, so the resolver only has to stop the
    region suffixes and casing from hiding the language.
    """
    text = (value or "").strip().lower()
    if not text:
        return ""
    return re.split(r"[\(\[]|[-_]", text)[0].strip()


def _product_dto(product):
    authors = ", ".join(
        a.get("name", "") for a in (product.get("authors") or [])
    )
    narrators = ", ".join(
        a.get("name", "") for a in (product.get("narrators") or [])
    )
    artist = authors + (" (narrated by " + narrators + ")" if narrators else "")
    minutes = (product.get("runtime_length_min") or 0)
    # No "album": the catalogue has none, and an album tag invented from the
    # provider name would be written into every exported audiobook.
    return {
        "title": product.get("title") or "",
        "artist": artist,
        "durationMs": int(minutes) * 60 * 1000,
        "authors": authors,
        "language": _language(product.get("language")),
    }


def resolve(url, keywords=""):
    """Resolve an Audible product URL/ASIN, or a keyword search."""
    try:
        host = _api_host(url)
        asin = None
        m = _PATH_ASIN_RE.search(url or "") or _ASIN_RE.search(url or "")
        if m:
            asin = m.group(1)
        if asin:
            data = _api(
                "https://"
                + host
                + "/1.0/catalog/products/"
                + asin
                + "?response_groups=product_attrs,contributors,product_desc"
            )
            product = data.get("product") if isinstance(data, dict) else None
            if not isinstance(product, dict) or not (product.get("title") or "").strip():
                return json.dumps({"ok": False, "error": "empty audible product"})
            dto = _product_dto(product)
            return json.dumps(
                {"ok": True, "kind": "book", "title": dto["title"], "tracks": [dto]}
            )
        query = keywords or urllib.parse.unquote(url or "")
        if not query.strip():
            return json.dumps({"ok": False, "error": "no asin or keywords"})
        data = _api(
            "https://"
            + host
            + "/1.0/catalog/products?keywords="
            + urllib.parse.quote(query)
            + "&num_results=1&response_groups=product_attrs,contributors,product_desc"
        )
        products = data.get("products") or []
        if not products:
            return json.dumps({"ok": False, "error": "no audible results"})
        first = products[0] if isinstance(products[0], dict) else None
        if not first or not (first.get("title") or "").strip():
            return json.dumps({"ok": False, "error": "empty audible product"})
        dto = _product_dto(first)
        return json.dumps(
            {"ok": True, "kind": "book", "title": dto["title"], "tracks": [dto]}
        )
    except Exception as e:  # noqa: BLE001 — bridge contract: failures are JSON
        return json.dumps({"ok": False, "error": str(e)})
