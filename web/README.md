# AudioVerse · Transmission — browser edition

`AudioVerse.html` is the whole app in one file, for the Mac Mini or a laptop. No install, no server, no account.

**Use it:** download `AudioVerse.html` and double-click it (Chrome or Edge recommended; Safari works except folder memory). Or host it on Cloudflare Pages.

| Tab | Notes |
|-----|-------|
| Library | **+ Add music folder** (your Music folder, an external drive). Chrome/Edge remember folders; click **Reconnect folders** on the next visit. Titles and artists come from file and folder names (Artist/Album/01 Title.flac or "Artist - Title"). Playlists and Favorites saved in this browser. |
| Radio | Same Transmission catalog as the phone app. |
| TV | The six free lists built in, plus your own link / pasted list / .m3u file. HLS and MPEG-TS via bundled hls.js and mpegts.js. Adult groups hidden unless shown. |
| DJ | Two decks, crossfader (Smooth / Linear / Scratch cut), timed fades, tempo ±8 % with pitch kept, per-deck level. |
| Watch | Music TV (YouTube no-cookie), videos from your folders or any video file. |

**Browser limits (the Android app has none of these):**
- Provider IPTV links (e.g. Gamma) usually don't send CORS headers, so a browser can't load them. Download the .m3u and use **Open file**, but the streams themselves may still be blocked; use the phone or Onn app for those.
- When hosted on https, plain-http streams are blocked (mixed content). Opening the file locally avoids that.
- No song titles from radio streams (ICY) and no EQ/limiter DSP.

**Privacy:** scripts are all inside the file (Content-Security-Policy blocks any other script source). Nothing is uploaded. Saved in this browser: playlists, settings, folder permissions, and an IPTV link only if you tick "Remember" (unencrypted — leave it off for links with a login; then it lasts for the session only).

**Rebuild:** `node web/build.mjs <hls.light.min.js> <mpegts.js>` (from npm `hls.js@1.5.20`, `mpegts.js@1.7.3`, Apache-2.0) after editing `app.html` or the station list.
