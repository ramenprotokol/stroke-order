"""Subset the two self-hosted fonts to the glyphs the app actually shows.

Maintainers only; the resulting .woff2 files are committed in web/fonts/, so a normal
build does not need Python or the network. Run from the repo root:

    uvx --from 'fonttools[woff]' python scripts/subset_fonts.py <dir with the source TTFs>

Sources (SIL Open Font License 1.1, no Reserved Font Names), from github.com/google/fonts:
    ofl/notoserifjp/NotoSerifJP[wght].ttf      -> web/fonts/serif-jp.woff2 (static weight 500)
    ofl/notosans/NotoSans[wdth,wght].ttf       -> web/fonts/sans-latin.woff2 (width 100, weight 400-700)
"""
import hashlib
import json
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "web" / "fonts"

# SHA-256 of the exact source files used for the committed subsets.
SOURCES = {
    "NotoSerifJP[wght].ttf": "2fd527ba12b6a44ec30d796d633360da0aeba6c5d4af1304ce12bb4dc15a7dfc",
    "NotoSans[wdth,wght].ttf": "bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d",
}

LATIN = (
    [chr(c) for c in range(0x20, 0x7F)]
    + [chr(c) for c in range(0xA0, 0x100)]
    + list("–—‘’“”•…·→←×")
)


def serif_text() -> str:
    curated = json.loads((ROOT / "data" / "curated.json").read_text("utf-8"))
    chars = set("書き順正筆")
    for g in curated["groups"]:
        chars.update(g["jp"])
        for k in g["kanji"]:
            chars.update(k["c"])
            for r in k["on"] + k["kun"]:
                chars.update(r)
    chars.update(chr(c) for c in range(0x3041, 0x3097))  # hiragana
    chars.update(chr(c) for c in range(0x30A1, 0x30FD))  # katakana + ー
    chars.update("「」、。・々〜")
    chars.update(LATIN)
    return "".join(sorted(chars))


def build(src: Path, dst: Path, text: str, axes: dict) -> None:
    font = TTFont(src)
    opts = subset.Options()
    opts.flavor = "woff2"
    opts.layout_features = ["kern", "liga", "palt", "vert", "vrt2", "locl"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    sub = subset.Subsetter(options=opts)
    sub.populate(text=text)
    sub.subset(font)
    # Narrow the variation axes after subsetting (fewer glyphs to instance).
    font = instancer.instantiateVariableFont(font, axes)
    font.flavor = "woff2"
    font.save(dst)
    print(f"{dst.relative_to(ROOT)}: {dst.stat().st_size} bytes, {len(set(text))} characters")


def main() -> None:
    src_dir = Path(sys.argv[1])
    for name, want in SOURCES.items():
        digest = hashlib.sha256((src_dir / name).read_bytes()).hexdigest()
        if digest != want:
            sys.exit(f"{name}: sha256 {digest} does not match the pinned {want}")
    OUT.mkdir(parents=True, exist_ok=True)
    build(src_dir / "NotoSerifJP[wght].ttf", OUT / "serif-jp.woff2", serif_text(), {"wght": 500})
    build(src_dir / "NotoSans[wdth,wght].ttf", OUT / "sans-latin.woff2", "".join(LATIN), {"wdth": 100, "wght": (400, 700)})


if __name__ == "__main__":
    main()
