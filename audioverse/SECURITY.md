# Security and privacy notes

| Area | Decision |
|------|----------|
| Server credentials | Jellyfin token and Cloudflare Access service token are encrypted with an AES-256-GCM key held in the Android Keystore. Only ciphertext reaches SharedPreferences. |
| Transport | Credentials are only attached to HTTPS requests, as headers, by a network interceptor that checks the host on every hop (including redirects). No `api_key` in URLs; the Jellyfin test refuses URLs that contain one. |
| Certificates | Only system CAs are trusted. User-installed CAs are ignored. |
| Cleartext | Allowed only because many radio streams are HTTP-only. No credentials are ever sent over HTTP. |
| Backup | `allowBackup=false` plus data-extraction rules exclude all app data from cloud backup and device transfer. |
| Logging | No HTTP logging interceptor. `AuthConfig.toString()` hides secrets. Test results contain no URLs, hosts or tokens. |
| UI secrets | Token fields are masked, held in memory only (not saved instance state) and cleared after use. "Forget saved login" wipes them. |
| Permissions | Internet, wake lock, foreground media service, notifications, and music/audio files only (READ_MEDIA_AUDIO) for the local library. Videos are opened one at a time through the system picker (no video permission). No photos, documents, location, contacts or microphone. |
| Video exports | Segments are written to Movies/AudioVerse via MediaStore; temp files in app storage are deleted after each export. The ffmpeg script single-quotes the source name and sanitises output names, so a file name cannot inject shell commands. Cut lists (names and times) stay on the phone. |
| Media session | Only this app and trusted system controllers (lock screen, Bluetooth, Android Auto) can connect. |
| Bit-perfect | Query only. Never switched on in this build (full-scale output risk, Council U23). |
| CI | Workflow permission `contents: read`. Optional debug keystore comes from a repository secret and is deleted after the build. The wrapper jar is validated by setup-gradle. |
| Revocation | If the phone is lost, revoke the Cloudflare Access service token and the Jellyfin token from their dashboards. Nothing else needs to change. |

| Radio catalog (v0.4) | Bundled in the APK (`stations.json`); nothing is downloaded to build it. Only http/https entries load. Station streams go through the same OkHttp client as before; no credentials are ever attached to them (the interceptor only adds headers for the configured Jellyfin/Access host). |
| Playlists (v0.4) | `files/playlists.json` in app-private storage: song ids, content URIs, titles and station URLs only. Excluded from backup and device transfer by the existing rules. |
| Music TV (v0.4) | WebView with JavaScript on for the YouTube embed only. `youtube-nocookie.com` embed, no JavaScript bridge, no file or content access, autoplay needs a tap. Any link the player opens leaves the app (YouTube app or browser) instead of loading inside it. The WebView is destroyed when you leave the tab. YouTube still sees your IP address and what you watch. |
| External links (v0.4) | Only http/https links are handed to other apps. |
| Phone video player (v0.4) | Same system file picker as the editor: one file at a time, no video or photo permission. |

| IPTV links (v0.5) | The M3U link contains the account's username and password. It is typed into a masked field with a password keyboard (no suggestions or learning), held only in memory until saved, then stored encrypted (AES-256-GCM, its own Android Keystore key `audioverse_iptv_v1`) in `audioverse_iptv` prefs, which backup and device transfer exclude. The screen shows only the name you gave it. It is never in the repo, logs, error messages or `toString()`. Remove deletes it, and removing the last playlist wipes the store. |
| IPTV channels (v0.5) | Channel lists (whose stream URLs also contain the login) stay in memory only; nothing is cached to disk. Channels can't be added to Library playlists for that reason. Logos are not loaded (no extra hosts contacted). |
| IPTV transport (v0.5) | Separate OkHttp client with no auth interceptor and no logging, so Jellyfin/Access credentials can never be sent to an IPTV host. If the link is http://, the app warns that the login travels unencrypted (many providers only offer http). If a provider rejects the AudioVerse user agent (401/403) the playlist request is retried once with a VLC user agent, and streams use that one too unless the playlist sets its own. |

| DJ decks (v0.6) | No new permissions or data. Decks use the same engine and network client as the player (credentials only go to the configured Jellyfin/Access host over HTTPS). Nothing about a mix is saved. Decks don't take audio focus, so a call or another app won't pause them automatically; they aren't on the lock screen, and the screen stays on while a deck plays. |
| Hearing | Each deck is limited (−4 dBFS on Smooth, −7 dBFS on Scratch cut) so the summed mix stays below 0 dBFS; Android's own volume and hearing-safety warning still apply on top. |

| Folders / USB drives (v0.7) | Read-only access to the folders you pick (system folder picker), nothing else on the drive or phone. No new storage or video permission. File names and tags are cached in `files/folder_tags.json` (app-private, not backed up). Removing a folder releases the access grant. |
| Open with / share (v0.7) | AudioVerse accepts only local files (content:// and file://) for audio, video and M3U, and text shares. It never claims web links. Shared or opened M3U content and links are shown in a confirm dialog and saved only if you tap Import; the shared text stays in memory until then. Files opened this way use Android's temporary read grant. |
| Pasted IPTV lists (v0.7) | Pasted M3U text or stream lists are stored encrypted with the IPTV Keystore key (capped at 1 MB) and deleted with Remove. The paste box uses a password keyboard so the keyboard doesn't learn the links. |

## Known follow-ups
- Station `WHUR World (HD2)` uses the same stream URL as `WHUR 96.3` in the catalog; check the HD2 URL.
- Pin GitHub Actions to commit SHAs once versions settle.
- Add `distributionSha256Sum` to the Gradle wrapper properties.
- Release signing key: create one only when distribution is approved, and store it outside the repo.
