#!/usr/bin/env python3
"""Writes the seed product illustrations (Phase 34) into catalog-service's classpath.

    python3 scripts/generate-product-images.py

They are flat, original SVGs drawn here: no photos, nothing downloaded, nothing third-party. They
are generated once and the output is committed; re-running rewrites the same bytes. Each is a
400x300 canvas with a tinted background and a simple shape for the product, plus a `<title>` so a
screen reader has a name. No scripts, no links, no external references (a test enforces that).
"""
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "catalog-service/src/main/resources/product-images"


def svg(title, bg, body):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300" width="400" height="300" '
        'role="img">\n'
        f"<title>{title}</title>\n"
        f'<rect width="400" height="300" rx="16" fill="{bg}"/>\n'
        f"{body}\n</svg>\n"
    )


def keys(x, y, cols, rows, w=24, gap=6, fill="#e8ecf4"):
    return "\n".join(
        f'<rect x="{x + c * (w + gap)}" y="{y + r * (w + gap)}" width="{w}" height="{w}" rx="4" fill="{fill}"/>'
        for r in range(rows) for c in range(cols)
    )


IMAGES = {
    "mechanical-keyboard.svg": svg("Mechanical Keyboard", "#dbe4f3",
        '<rect x="50" y="95" width="300" height="130" rx="14" fill="#33415c"/>\n' + keys(68, 112, 9, 3, 24, 8)),
    "wireless-mouse.svg": svg("Wireless Mouse", "#e3f1e6",
        '<path d="M200 60c45 0 70 30 70 75v45c0 40-30 65-70 65s-70-25-70-65v-45c0-45 25-75 70-75z" fill="#3d5a45"/>\n'
        '<rect x="196" y="75" width="8" height="55" rx="4" fill="#e3f1e6"/>'),
    "monitor-4k.svg": svg("27 inch 4K Monitor", "#e9e4f5",
        '<rect x="55" y="50" width="290" height="170" rx="10" fill="#2b2d42"/>\n'
        '<rect x="66" y="61" width="268" height="148" rx="4" fill="#6c8ebf"/>\n'
        '<rect x="180" y="220" width="40" height="30" fill="#2b2d42"/>\n'
        '<rect x="140" y="248" width="120" height="10" rx="5" fill="#2b2d42"/>'),
    "noise-cancelling-headphones.svg": svg("Noise-Cancelling Headphones", "#f6e6e1",
        '<path d="M110 190v-40a90 90 0 0 1 180 0v40" fill="none" stroke="#5b2a24" stroke-width="16" stroke-linecap="round"/>\n'
        '<rect x="88" y="170" width="46" height="80" rx="18" fill="#8a4034"/>\n'
        '<rect x="266" y="170" width="46" height="80" rx="18" fill="#8a4034"/>'),
    "usb-c-hub.svg": svg("USB-C Hub", "#e1eff5",
        '<rect x="70" y="110" width="260" height="80" rx="16" fill="#355c7d"/>\n'
        + "\n".join(f'<rect x="{95 + i * 50}" y="138" width="34" height="22" rx="4" fill="#e1eff5"/>' for i in range(5))),
    "laptop-stand.svg": svg("Laptop Stand", "#f3eedd",
        '<path d="M110 220l90-120 90 120" fill="none" stroke="#6b5a2a" stroke-width="14" stroke-linejoin="round"/>\n'
        '<rect x="100" y="90" width="200" height="14" rx="7" fill="#8a7632" transform="rotate(-12 200 97)"/>\n'
        '<rect x="90" y="224" width="220" height="12" rx="6" fill="#6b5a2a"/>'),
    "webcam-1080p.svg": svg("Webcam 1080p", "#ecebe8",
        '<circle cx="200" cy="130" r="62" fill="#2f2f33"/>\n'
        '<circle cx="200" cy="130" r="36" fill="#4a6fa5"/>\n'
        '<circle cx="200" cy="130" r="14" fill="#14213d"/>\n'
        '<rect x="180" y="192" width="40" height="40" fill="#2f2f33"/>\n'
        '<rect x="150" y="230" width="100" height="10" rx="5" fill="#2f2f33"/>'),
    "desk-mat.svg": svg("Desk Mat", "#efe6da",
        '<rect x="40" y="80" width="320" height="150" rx="14" fill="#7a6a58"/>\n'
        '<rect x="52" y="92" width="296" height="126" rx="8" fill="none" stroke="#efe6da" stroke-width="3" stroke-dasharray="10 8"/>'),
}

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, content in IMAGES.items():
        (OUT / name).write_text(content, encoding="utf-8")
    print(f"wrote {len(IMAGES)} files to {OUT}")
