"""Fetch the embed page for a public instagram reel and report whether the
video URL is present. Uses the same parse rules as sleeve_engine.instagram_embed."""
import re
import json
import sys
import urllib.request

code = sys.argv[1] if len(sys.argv) > 1 else "CtjoC2-BNfS"
for path in (
    f"https://www.instagram.com/reel/{code}/embed/captioned/",
    f"https://www.instagram.com/p/{code}/embed/captioned/",
):
    try:
        req = urllib.request.Request(path, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            body = resp.read(1 << 20).decode("utf-8", "ignore")
        print(path, "->", len(body), "bytes")
        v = re.search(r'"video_url":"((?:[^"\\]|\\.)*)"', body)
        og = re.search(r'property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)', body)
        img = re.search(r'class="EmbeddedMediaImage"[^>]*src="([^"]+)"', body)
        if v:
            print("  video_url:", json.loads('"%s"' % v.group(1))[:120])
        if og:
            print("  og:video:", og.group(1)[:120])
        if img:
            print("  image:", img.group(1)[:120])
        if v or og or img:
            break
    except Exception as e:
        print(path, "-> ERROR", e)
