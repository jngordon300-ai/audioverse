# AudioVerse

Personal audiophile and DJ platform for Android (Samsung Galaxy S26 Ultra + Galaxy Buds 4 Pro).

**Modules:** AudioVerse Player · AudioVerse DJ · AudioVerse Radio · AudioVerse Studio (future AI stems) · AudioVerse Server (Mac Mini companion)

Architecture: *AudioVerse — Architecture Plan v1.2* (claude.ai doc). Engine: AndroidX Media3, with libVLC as a fallback backend later on.

## Current build: v0.12.0-alpha

v0.12 (Council Phase 1): stricter home-server http rule (VPN or Trusted home Wi-Fi), Emby-compatible sign-in, auto Data saver on mobile data, free TV lists opt-in, browser edition without "Remember", Cloudflare static-site config, public APK publishing held until verified (repo variable `PUBLISH_APK=true`).

### Earlier versions: v0.8.0-alpha (free TV lists, Onn / Google TV, browser edition in `web/`); v0.7.0 added USB drives, M3U lists, default player; v0.6.0 added DJ; v0.5.0 added IPTV; v0.4.0-alpha — AudioVerse + Transmission Across the Diaspora

One app for the phone's music and live radio, plus video:

| Tab | What it does |
|-----|--------------|
| **Library** | Categories: Songs · Artists · Albums · Playlists. Playlists mix phone songs and live stations; Favorites is built in. `+` adds anything to a playlist; ↑ ↓ ✕ reorder and remove inside a playlist. |
| **Library → Rescan / Folders** | v0.7: **Rescan** rereads the phone library and your folders. **Folders → + Add folder** picks a folder on the phone, SD card or a USB drive (e.g. Samsung T5) with the system picker; AudioVerse finds MP3, FLAC, Opus, M4A, WAV, AIFF… and videos in it (subfolders included). It rescans automatically when a drive is plugged in or the phone's media index changes. Unplugged drives show "Not connected". Songs also on the phone's index aren't listed twice. Folder videos appear in Video → Watch. |
| **Open with AudioVerse** | v0.7: AudioVerse appears in "Open with" for music (MP3, FLAC, Opus, M4A…), video (MP4, MKV, WebM…) and M3U files. Pick **Always** to make it the default player. Music plays right away, videos open in Watch, M3U files go to Video → TV after you confirm. Share text with IPTV links to AudioVerse to import them. |
| **Radio** | The Transmission catalog (`app/src/main/assets/stations.json`, 166 entries, 75 live streams). Category chips (Featured, Philly, Hip-Hop, R&B, Gospel, Jazz, Talk, HBCU, Afrobeats, Amapiano, Reggae, UK, Podcasts, SiriusXM…), search and Shuffle. Live streams play in the AudioVerse engine, so the EQ, limiter, lock screen and Buds controls apply; next / previous move to the next station. Song titles from the stream (ICY) show in the app and on the lock screen. Podcasts, SiriusXM and other platforms open in their own app. |
| **Playing** | Now playing. Live stations show LIVE and the current song title instead of a seek bar. ♥ Favorite and + Playlist. |
| **EQ presets** | v0.11: Sound → **Presets**: Flat, Boom Bap, Bass Boost, R&B Warm, Vocal, Bright, Late Night, Earbuds. One tap sets the curve and a matching preamp (minus the biggest boost) so it doesn't just hit the limiter. **Save mine** stores up to 8 of your own. |
| **Search** | v0.11: Library search box (song, artist, album or folder) works in every category, plus a new **Folders** category. **Search** on Now Playing finds anything in the library and plays the results in order. |
| **Home server** | v0.10: Library → **Server** signs in to Jellyfin on the Mac Mini. Its songs join the Library (and DJ, playlists); its videos appear in Watch. Home / VPN address first (Wi-Fi or Tailscale/Meshnet), Cloudflare Tunnel + Access as backup. **Data saver** asks the server for MP3 192k / 4 Mbps video. Setup: `docs/HOME-SERVER.md`. |
| **Video folders & playlists** | v0.9: Video → Watch → **+ Add video folder** (phone, SD or USB drive). Tapping a folder video plays on through the rest. **+** on any video adds it to a video playlist; playlists play all, reorder (↑ ↓), remove (✕); **Up next** with ◀ ▶ while a list plays. |
| **Free TV lists** | v0.8: six no-login playlists built into Video → TV (IPTV-Org US / by category / worldwide, Free-TV, Pluto TV US, Samsung TV Plus US). Unofficial, community-run; some channels will be offline. Adult groups (XXX) hidden unless you tap Show. |
| **Onn / Google TV** | v0.8: the same APK installs on Android TV / Google TV boxes (Onn 4K, Onn 4K Pro), appears on the TV home screen with a banner, and works with the remote's D-pad and play/pause. Touch-only bits (the control wheel) aren't needed on TV. |
| **TV imports** | v0.7: in Video → TV → Add, **Paste a list** takes M3U text or one link per line ("Name, link" allowed): provider playlist links (get.php, .m3u) are saved one by one, single stream links become one channel list. **Pick file** also takes a .txt of links. |
| **DJ** | v0.6: two decks (A / B) and a crossfader. Load phone songs (MP3, FLAC, lossless), playlist entries or live stations on either deck. Play, Cue, ±5 s nudge, tempo ±8 % with pitch kept, per-deck level. Crossfader with centre detent and three curves (Smooth blend, Linear, Scratch cut); Fade to A / Fade to B blends automatically over 4–32 s and stops the deck that faded out. Each deck runs its own copy of your Sound settings (ReplayGain levels the two songs) with a lowered limiter ceiling so the mix can't clip. Screen stays on while a deck plays. |
| **Video** | **Watch**: Music TV channels (YouTube no-cookie embed of each channel's uploads, full screen supported) and a player for any video you pick from the phone. **Edit**: the existing cut editor (Mark start / End, export segments to Movies/AudioVerse). "Cut it ›" moves from Watch to Edit at the same spot. |
| **Video → TV** | IPTV (v0.5): paste your provider's M3U link (or pick an .m3u file). Channels load by group with search; tap to watch, ◀ ▶ to change channel, full screen supported. HLS and MPEG-TS streams; links without an extension are tried as TS, then HLS. Several playlists can be saved and switched with the chips. |
| **Sound** | DSP settings. **Engine tests ›** at the top opens the Phase 1 gate screen. |

To update the station list, edit `stations.json` (same fields as the Transmission HTML: name, city, tags, cat, type, url, ytChannel).

## Phase 1 engine gate (v0.1.0-gate)

A single-screen test app that answers the Phase 1 questions on real hardware:

| # | Test | Passes when |
|---|------|-------------|
| 1 | Gapless playback | Longest digital silence at the track change is under 5 ms (provisional) and there's no audible gap |
| 2 | 24-bit / float through DSP | The DSP chain receives 32-bit float at the file's own sample rate |
| 3 | Jellyfin + Cloudflare Access | Header-only login works, a seek uses an HTTP 206 range request, there's no token in any URL, and credentials only go over HTTPS |
| 4 | Radio now-playing (ICY) | A stream title arrives within 30 s |
| 5 | Bit-perfect USB query | The phone reports at least one bit-perfect format for the attached DAC (query only) |
| 6 | Screen-locked playback | Audio keeps playing for 60 s with the screen locked (you report the result) |

Run tests 1 and 2 with **Float output** both ON and OFF. That shows whether Media3 runs custom audio processors on hi-res float audio. If it doesn't, the DSP needs a custom audio sink.

"Copy results" puts a text report on the clipboard for the AiVerse Council. It contains no URLs, hostnames or tokens.

## Get the APK

1. Push to `main` (or Actions → Android build → Run workflow).
2. Open the finished run → Artifacts → `audioverse-gate-debug-apk`.
3. On the phone, allow installs from your browser or file manager, then open the APK.

**Stable signing (recommended):** without it, each build is signed with a new throwaway key, and you have to uninstall before installing the next build. To fix that, create a debug keystore once and add it as the repository secret `DEBUG_KEYSTORE_BASE64`:

```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -i debug.keystore | pbcopy   # macOS; then paste into GitHub → Settings → Secrets → Actions
```

Don't commit the keystore. `.gitignore` blocks it.

## Layout

```
app/            Phase 1 gate app (Compose), PlaybackService (MediaSession)
core/dsp/       DSP chain: ReplayGain, preamp, vocal stage, 10-band EQ, channel mixer, limiter (+ unit tests)
core/network/   Header-only auth interceptor, Keystore credential store, privacy-safe HTTP recorder
core/playback/  PlaybackBackend interface + Media3Backend
```

## Versions

AGP 8.7.3 · Kotlin 2.0.21 · Media3 1.5.1 · compileSdk 35 · minSdk 29. These are known-good versions picked for the first green build. Bump them once CI passes.
