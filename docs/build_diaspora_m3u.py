#!/usr/bin/env python3
"""
Builds ~/Media/IPTV/diaspora.m3u: the default "Transmission TV" lineup for AudioVerse and the
Mac Mini's Emby Live TV tuner.

Groups (in this order):
  Philadelphia · News · Hip-Hop Videos · R&B / Soul Videos · Gospel ·
  African American · Caribbean · Africa

v2 changes:
- Added Philadelphia locals, national news (CNN Headlines, NBC News NOW, TYT Network, …) and
  Hip-Hop / R&B / Soul music-video channels.
- News and music channels are matched by exact channel name, so look-alikes don't slip in.
- Uses iptv-org's raw stream files on raw.githubusercontent.com (same data as iptv-org.github.io,
  reachable from more networks) and the BuddyChewChew Pluto / Samsung / Plex / Roku lists.
- The same channel from two sources appears once (first source wins).

Not available free and legally: full CNN, MSNBC / MS NOW, and the 6abc / NBC10 / FOX 29 / PHL17
broadcast feeds. The closest free channels are included (CNN Headlines, NBC News NOW, CBS News
Philadelphia, LiveNOW from FOX). The local stations themselves are free over the air with an antenna.

No logins, no secrets: every source is a public playlist. Running it only reveals the Mac Mini's IP
address to GitHub and the stream hosts (FAST streams go through the third-party jmp2.uk proxy).
Run it by hand; scheduling it is a separate decision.
"""
import re
import urllib.request
from pathlib import Path

OUTPUT_M3U = Path.home() / "Media/IPTV/diaspora.m3u"

IPTV_ORG = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/{}.m3u"
FAST = "https://raw.githubusercontent.com/BuddyChewChew/app-m3u-generator/main/playlists/{}.m3u"

US_LISTS = [FAST.format("plutotv_us"), FAST.format("samsungtvplus_us"), FAST.format("plex_us"),
            FAST.format("roku_all"), IPTV_ORG.format("us"), IPTV_ORG.format("us_local")]
AFRICA = [IPTV_ORG.format(c) for c in ("ng", "gh", "za", "ke", "sn", "et")]
CARIBBEAN = [IPTV_ORG.format(c) for c in ("jm", "tt", "bb", "ht", "bs", "gy")]

def exact(*names: str) -> re.Pattern:
    """Matches a channel name exactly (case-insensitive), ignoring a trailing "(720p)"-style tag."""
    return re.compile(r"^(?:" + "|".join(re.escape(n) for n in names) + r")$", re.IGNORECASE)

# (group, pattern, sources). Order matters: a channel goes to the first group that claims it.
RULES = [
    ("Philadelphia", exact("CBS News Philadelphia", "PhillyCAM"), US_LISTS),
    ("News", exact("CNN Headlines", "NBC News NOW", "TYT Network", "ABC News Live", "CBS News 24/7",
                   "LiveNOW from FOX", "FOX SOUL", "TheGrio", "Black Star Network"), US_LISTS),
    ("Hip-Hop Videos", exact("Vevo Hip-Hop", "Vevo True School Hip-Hop", "Stingray Hip Hop",
                             "XITE Hip-Hop Evolution", "XITE Rap Game", "Hip-Hop/R&B", "TRACE Urban",
                             "VH1+ Hip Hop & Rap"), US_LISTS),
    ("R&B / Soul Videos", exact("Vevo R&B", "XITE R&B Classic Jams", "XITE R&B Feels", "Stingray Soul Storm",
                                "XITE 90s Throwback", "XITE 60s & 70s Groove"), US_LISTS),
    ("Gospel", exact("XITE Gospel"), US_LISTS),
    ("African American", re.compile(
        r"\b(BET|Tyler Perry|ALLBLK|Black Family Sitcoms|Black Cinema|Bounce|Ebony|Aspire|TV One|CLEO|"
        r"REVOLT|Nolly|Afro|Brown Sugar|Kevin Hart|HBCU|Shades of Black|BlackPix|FilmRise Black|Urban TV)\b",
        re.IGNORECASE), US_LISTS[:4]),          # FAST lists only, filtered
    ("Caribbean", re.compile(r".*"), CARIBBEAN),  # whole country lists
    ("Africa", re.compile(r".*"), AFRICA),
]


def fetch_lines(url: str, cache: dict) -> list[str]:
    if url not in cache:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "AudioVerse-M3U-Builder/2.0"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                cache[url] = resp.read().decode("utf-8", errors="ignore").splitlines()
        except Exception as exc:  # one dead source shouldn't stop the rest
            print(f"Skipping {url}: {exc}")
            cache[url] = []
    return cache[url]


def entries(lines: list[str]):
    """Yields (extinf, extra_option_lines, url) for each channel."""
    i = 0
    while i < len(lines):
        if lines[i].startswith("#EXTINF"):
            extinf, extras, j = lines[i], [], i + 1
            while j < len(lines) and lines[j].strip().startswith("#") and not lines[j].startswith("#EXTINF"):
                if lines[j].startswith("#EXTVLCOPT"):
                    extras.append(lines[j].strip())  # keep user-agent / referrer hints
                j += 1
            if j < len(lines) and lines[j].strip().startswith("http"):
                yield extinf, extras, lines[j].strip()
            i = j
        else:
            i += 1


def channel_name(extinf: str) -> str:
    name = extinf.split(",", 1)[-1].strip()
    return re.sub(r"\s*\((?:\d+[pi]|SD|HD)\)\s*$", "", name).strip()  # "CBS News Philadelphia (720p)"


def rewrite_group(extinf: str, group: str) -> str:
    if 'group-title="' in extinf:
        return re.sub(r'group-title="[^"]*"', f'group-title="{group}"', extinf)
    return re.sub(r"^#EXTINF:(-?\d+)", rf'#EXTINF:\1 group-title="{group}"', extinf, count=1)


def main() -> None:
    OUTPUT_M3U.parent.mkdir(parents=True, exist_ok=True)
    cache: dict = {}
    out = ["#EXTM3U"]
    seen_urls: set[str] = set()
    seen_names: set[str] = set()
    counts: dict[str, int] = {}

    for group, pattern, sources in RULES:
        for src in sources:
            for extinf, extras, url in entries(fetch_lines(src, cache)):
                name = channel_name(extinf)
                key = name.lower()
                if url in seen_urls or key in seen_names:
                    continue
                if group in ("Caribbean", "Africa"):
                    ok = True
                elif group == "African American":
                    ok = bool(pattern.search(name))
                else:
                    ok = bool(pattern.match(name))
                if not ok:
                    continue
                seen_urls.add(url)
                seen_names.add(key)
                counts[group] = counts.get(group, 0) + 1
                out.append(rewrite_group(extinf, group))
                out.extend(extras)
                out.append(url)

    tmp = OUTPUT_M3U.with_suffix(".tmp")
    tmp.write_text("\n".join(out) + "\n", encoding="utf-8")
    tmp.replace(OUTPUT_M3U)
    print(f"Wrote {len(seen_urls)} channels to {OUTPUT_M3U}")
    for group, *_ in RULES:
        print(f"  {group}: {counts.get(group, 0)}")


if __name__ == "__main__":
    main()
