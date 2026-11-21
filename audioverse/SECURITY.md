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

## Known follow-ups
- Pin GitHub Actions to commit SHAs once versions settle.
- Add `distributionSha256Sum` to the Gradle wrapper properties.
- Release signing key: create one only when distribution is approved, and store it outside the repo.
