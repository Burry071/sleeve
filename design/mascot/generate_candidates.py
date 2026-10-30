"""Generate six Sleeve mascot candidates via Nano Banana Pro.

Skill: ip-as-logo (one-pass creative draw, no retries, deliver as-is).
Prompts follow the skill's complete prompt skeleton; each candidate varies
only subject, the two IP base colors, and the background color.
"""
import base64
import json
import os
import sys
import urllib.request

KEY = os.environ["MASCOT_KEY"]
MODEL = os.environ.get("MASCOT_MODEL", "gemini-3-pro-image")
OUT = os.path.dirname(os.path.abspath(__file__)) + "/candidates"
OPENROUTER_KEY = os.environ.get("OPENROUTER_KEY", "")

SKELETON = """Create one complete full-bleed 1:1 square image.
Background: fill the entire square with solid {bg}. Keep {bg} visible in every open area and in the corners not occupied by the character; the assigned emergence corner must be occupied by the character.
Subject: place one extremely simplified, cute, endearing {subject} IP character on the background, reduced to one soft rounded continuous silhouette and one defining feature.
Complexity: use only 4-7 large basic shapes and at most two broad internal color regions. Use two simple eyes and add one tiny mouth only when it helps the expression. Remove every nonessential line, outline, anatomical detail, texture, and decoration. Keep the character readable at 32 x 32.
Color behavior: use exactly three semantic colors in the complete image: exactly two IP base colors plus the background color. The two IP base colors for this character are {c1} and {c2}; organize both into broad purposeful masses, and reuse them for facial marks. Lower the background saturation slightly so it feels gently muted and restrained while remaining clearly chromatic, clean, and intentional rather than gray or muddy. Keep the IP, facial marks, and background clearly separated.
Composition: keep the character upright and emerging from the lower-left corner, filling about 85-95% of the square so it remains visually dominant. Cropping at the bottom or left side is welcome when it strengthens the corner emergence. Preserve both paired identifying features. Never center or bottom-center the character.
Style: make simplification, cuteness, and lovable baby-like appeal the strongest qualities. Use large soft forms, compact proportions, thick rounded contours, and an ultra-clean graphic treatment. Prefer one clear shape over several explanatory details. Add an extremely, extremely subtle, almost imperceptible sense of depth through a barely-there neo-skeuomorphic treatment.
Finish: show only the character on the full-canvas background, with clean surfaces and normal square outer corners.
Constraints: Use no text or watermark. Add no borders, frames, cards, or presentation masks. Include one character only, with no extra subjects or scenery. Use no fragile lines, sharp tips, unnecessary outlines, tiny details, or decorative marks. Add no photorealistic material, dramatic bevel, glossy hotspot, deep occlusion, extrusion, strong three-dimensional rendering, or external cast shadow. Keep the background solid and uniform, with no texture, vignette, or lighting variation."""

CANDIDATES = {
    # A — baby ghost (the embedded Ghost engine, made huggable)
    "A1": dict(
        subject="baby ghost with a soft dome head and a wavy sheet hem, tiny nub arms raised",
        c1="soft cloud white for the whole body",
        c2="deep ocean teal used only for the two simple eyes, a tiny mouth, and two soft round blush cheeks",
        bg="muted deep ocean teal",
    ),
    "A2": dict(
        subject="baby ghost with a soft dome head and a wavy sheet hem, tiny nub arms tucked in, sleeping-calm face",
        c1="gentle seafoam teal for the whole body",
        c2="deep pine teal used only for the two simple eyes, a tiny mouth, and two soft round blush cheeks",
        bg="muted warm cream",
    ),
    # B — cozy seal pup wrapped in a knit sleeve (Seal lineage + Sleeve name)
    "B1": dict(
        subject="plump baby seal pup snugly wrapped in one thick knit sweater sleeve so only its round head and one flipper nub peek out, the sleeve cuff forming one broad defining region",
        c1="soft powder cream for the seal head and flipper",
        c2="warm coral knit for the sweater sleeve and cuff",
        bg="muted deep ocean teal",
    ),
    "B2": dict(
        subject="plump baby seal pup snugly wrapped in one thick knit sweater sleeve so only its round head and one flipper nub peek out, the sleeve cuff forming one broad defining region, calm sleepy eyes",
        c1="soft warm latte cream for the seal head and flipper",
        c2="deep pine teal knit for the sweater sleeve and cuff",
        bg="muted warm sand",
    ),
    # C — vinyl record critter peeking from a sleeve pocket (media in its sleeve)
    "C1": dict(
        subject="round vinyl-record-headed creature with a soft disc head, peeking out of one thick rounded pocket collar pulled up around its lower half, the pocket forming one broad defining region",
        c1="soft cream for the disc head rim and face area",
        c2="deep ocean teal for the round center label of the disc and the pocket",
        bg="muted warm sand",
    ),
    "C2": dict(
        subject="round vinyl-record-headed creature with a soft disc head, peeking out of one thick rounded pocket collar pulled up around its lower half, the pocket forming one broad defining region, gentle smile",
        c1="warm latte cream for the disc head rim and face area",
        c2="deep pine teal for the round center label of the disc and the pocket",
        bg="muted soft coral",
    ),
}


def generate(label: str, spec: dict) -> None:
    prompt = SKELETON.format(**spec)
    if OPENROUTER_KEY:
        body = json.dumps(
            {
                "model": MODEL,
                "messages": [{"role": "user", "content": [{"type": "text", "text": prompt}]}],
            }
        ).encode()
        req = urllib.request.Request(
            "https://openrouter.ai/api/v1/chat/completions",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {OPENROUTER_KEY}",
            },
        )
    else:
        body = json.dumps({"contents": [{"parts": [{"text": prompt}]}]}).encode()
        req = urllib.request.Request(
            f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent",
            data=body,
            headers={"Content-Type": "application/json", "x-goog-api-key": KEY},
        )
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            data = json.load(r)
    except urllib.error.HTTPError as e:
        print(f"{label}: HTTP {e.code}: {e.read()[:200]!r}")
        return
    if OPENROUTER_KEY:
        msg = data.get("choices", [{}])[0].get("message", {})
        images = msg.get("images") or []
        for image in images:
            url = image.get("image_url", {}).get("url", "")
            if url.startswith("data:"):
                payload = url.split(",", 1)[1]
                img = base64.b64decode(payload)
                path = f"{OUT}/{label}.png"
                with open(path, "wb") as f:
                    f.write(img)
                print(f"{label}: saved {len(img)} bytes -> {path}")
                return
        diag = json.dumps(data)[:600]
        print(f"{label}: NO IMAGE in response: {diag}")
        return
    parts = data.get("candidates", [{}])[0].get("content", {}).get("parts", [])
    for part in parts:
        if "inlineData" in part:
            img = base64.b64decode(part["inlineData"]["data"])
            path = f"{OUT}/{label}.png"
            with open(path, "wb") as f:
                f.write(img)
            print(f"{label}: saved {len(img)} bytes -> {path}")
            return
    diag = json.dumps(data)[:600]
    print(f"{label}: NO IMAGE in response: {diag}")


def main() -> None:
    only = sys.argv[1:] or list(CANDIDATES)
    for label in only:
        generate(label, CANDIDATES[label])


if __name__ == "__main__":
    main()
