# Home server: Mac Mini (Jellyfin) → AudioVerse anywhere

AudioVerse streams the music and videos on the Mac Mini's external drives through **Jellyfin**. Two ways in, and the app picks whichever works:

| Path | When | Exposure |
|------|------|----------|
| **Home / VPN** — `http://<private IP>:8096` | At home on Wi-Fi, or away with the VPN on | None. The Mac Mini is never on the public internet. |
| **Cloudflare** — `https://media.<your domain>` | Away, no VPN app needed | A public hostname, but Cloudflare Access blocks everyone without your service token (the app) or your email login (browser). |

The app tries Home / VPN first and checks that the server answering is the same Jellyfin it signed in to (by server ID) before sending anything. Then it falls back to Cloudflare.

## 1. Rebuild Jellyfin on the Mac Mini
1. Install the current Jellyfin for macOS from jellyfin.org (or the Docker image), open `http://localhost:8096`, and create an admin user. Then create a **second, non-admin user** for the phone (e.g. `jamel-phone`): it gets library access only.
2. Add libraries pointing at the external drives:
   - **Music** → e.g. `/Volumes/easystore/Music` (content type *Music*)
   - **Movies / Shows / Videos** → your video folders (content types *Movies*, *Shows*, *Home videos*)
3. Dashboard → Playback: leave transcoding on (AudioVerse's **Data saver** uses it; otherwise it plays the original files).
4. Dashboard → Networking: keep **HTTPS off** in Jellyfin (Cloudflare provides it) and **remote access** on. Don't open or forward any router port.
5. macOS: System Settings → Energy → turn on *Prevent automatic sleeping* and *Wake for network access*, so the Mac Mini answers at 2 a.m. too.

## 2. Home / VPN path (recommended first)
- **At home:** give the Mac Mini a fixed IP in your router (DHCP reservation), e.g. `192.168.1.50`. App address: `http://192.168.1.50:8096`.
- **On the go:** install **Tailscale** (free) on the Mac Mini, the S26 and the laptop, signed in to the same account (or use the mesh VPN you already run). The Mac Mini gets a `100.x.y.z` address. App address: `http://100.x.y.z:8096`. That one address works at home and away, as long as the VPN is on.
- The app only accepts plain `http://` for private IPs (`10.*`, `172.16–31.*`, `192.168.*`, `100.64–127.*`), never for hostnames or public IPs.
- **v0.12:** the token goes to the http address only while Android reports your **VPN is on**. To use the home address on home Wi-Fi without a VPN, switch on **Trusted home Wi-Fi** in Library → Server (off by default; never on public Wi-Fi). Simplest: leave Tailscale on, and use its `100.x` address everywhere.
- **Emby (Chair ruling: Emby is the single server):** the same Server screen works with Emby. Use the Emby address (default port `8096`) and an Emby user; AudioVerse sends `X-Emby-Token` as a header. Your Emby Premiere key stays in Emby's own settings on the Mac Mini; AudioVerse never needs it.

## 3. Cloudflare path (backup, no VPN needed)
1. Cloudflare dashboard → **Zero Trust → Networks → Tunnels → Create a tunnel** (cloudflared). Install the connector on the Mac Mini with the command Cloudflare shows (`brew install cloudflared`, then the `cloudflared service install <token>` line).
2. Add a **public hostname**: `media.jaygordonai.com` → Service `http://localhost:8096`.
3. **Zero Trust → Access → Applications → Add → Self-hosted** for `media.jaygordonai.com`, with two policies:
   - **Service Auth** policy that includes a **Service Token** you create (Access → Service credentials → Service tokens). Copy the *Client ID* and *Client Secret* into AudioVerse → Library → Server. They're shown once and stored encrypted in the app.
   - **Allow** policy for your email (one-time PIN) so a browser can log in.
4. Test from a browser on mobile data: `https://media.jaygordonai.com` should ask for your email first.

## 4. Connect AudioVerse
Library → **Server** → fill the Home / VPN address and/or the Cloudflare address, the Jellyfin username and password (used once to get a token; not saved), and the Cloudflare service token. **Connect**. Songs join the Library (Songs, Artists, Albums, playlists, DJ); videos appear in Video → Watch → *From your home server*. **Data saver** switches to MP3 192k and 4 Mbps video for mobile data.

## 5. Browser on the go
Use **Jellyfin's own web app**: `https://media.jaygordonai.com` (Cloudflare email login), or `http://100.x.y.z:8096` with Tailscale on. The AudioVerse browser file doesn't talk to Jellyfin yet: browsers can't attach the login headers to audio and video, and the alternative (token in the link) is what this setup avoids.

## Security notes
- Revoke access any time: Jellyfin Dashboard → Devices (sign the phone out), and Cloudflare → Service tokens (revoke). Sign out in the app also revokes the token.
- Use the non-admin Jellyfin user on the phone.
- The token and Access keys live in the phone's Keystore-encrypted storage, and travel only as headers: never in links, logs or backups.
- Over the private path, traffic is plain http **inside** your home network or VPN tunnel (the VPN encrypts it). On hotel Wi-Fi without the VPN, a stranger's device could sit at the same private IP; the server-ID check stops the app from talking to it.
