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

# Deeper embed gql markers, scanned after the legacy video_url/og:video pair
# (same order as sleeve_engine.instagram_embed's ladder): progressive
# video_versions blobs, then playable_url.

vv_body = (
    '<script type="application/json" data-len="16">'
    '{"gql_data":{"shortcode_media":{"video_versions":'
    '[{"width":720,"url":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t50.hi.mp4?hi=1"},'
    '{"width":360,"url":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t50.lo.mp4?lo=1"}],'
    '"is_video":true}}}</script>'
)

vv_target = None
for vm in re.finditer(r'"video_versions":\[', vv_body):
    um = re.search(r'"url":"((?:[^"\\]|\\.)*)"', vv_body[vm.end() : vm.end() + 600])
    if um:
        vv_target = json.loads('"%s"' % um.group(1))
        break
print("video_versions target:", vv_target)
assert vv_target == "https://scontent.xx.fbcdn.net/v/t50.hi.mp4?hi=1"

pu_body = '{"gql_data":{"context":{"video_versions":[],"listen":false},"playable_url":"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t50.p.mp4"}}'
pu = re.search(r'"playable_url":"((?:[^"\\]|\\.)*)"', pu_body)
print("playable_url:", pu.group(1) if pu else None)
assert pu and json.loads('"%s"' % pu.group(1)) == "https://scontent.xx.fbcdn.net/v/t50.p.mp4"
