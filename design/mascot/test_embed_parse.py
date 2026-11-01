import re
import json

body = (
    '<meta property="og:video" content="https://scontent.cdninstagram.com/v/video.mp4?_nc=1" />'
    '<script>window.__additionalDataLoaded("extra",{"video_url":"https:\\/\\/scontent.xx.fbcdn.net'
    '\\/v\\/t50.reel.mp4?efg=eyJ1cmwi\\u0026oh=abc"});</script>'
    '<img class="EmbeddedMediaImage" src="https://scontent.cdninstagram.com/img.jpg" />'
)

target = None
video_json = re.search(r'"video_url":"((?:[^"\\]|\\.)*)"', body)
og_video = re.search(
    r'property=["\']og:video(?::secure_url)?["\'][^>]+content=["\']([^"\']+)', body
)
raw_video = video_json.group(1) if video_json else (og_video.group(1) if og_video else None)
if raw_video:
    target = json.loads('"%s"' % raw_video)
print("video target:", target)

if not target:
    img = re.search(r'class="EmbeddedMediaImage"[^>]*src="([^"]+)"', body)
    print("img fallback:", img.group(1) if img else None)
