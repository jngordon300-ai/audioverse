package com.jaygordonai.audioverse.app.server

import android.content.Context
import android.net.Uri
import com.jaygordonai.audioverse.app.PlayerHolder
import com.jaygordonai.audioverse.app.player.Track
import com.jaygordonai.audioverse.network.AuthConfig
import com.jaygordonai.audioverse.network.SecretPrefs
import com.jaygordonai.audioverse.network.SecureHeaderInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A video on the home server, for Video → Watch. */
data class ServerVideo(val uri: Uri, val name: String, val sub: String)

/** What the screen shows about the server. Never addresses with logins, never the token. */
data class ServerStatus(
    val configured: Boolean = false,
    val serverName: String = "",
    val via: String? = null,          // "home network / VPN" or "Cloudflare"
    val error: String? = null,
    val songs: Int = 0,
    val videos: Int = 0,
    val dataSaver: Boolean = false,
    val autoSaver: Boolean = true,
    val trustHomeWifi: Boolean = false,
    val homeAddress: String = "",      // shown so you can edit it (no login inside)
    val remoteAddress: String = ""
)

data class ServerLibrary(val tracks: List<Track>, val videos: List<ServerVideo>, val status: ServerStatus)

/**
 * Jellyfin on the Mac Mini, reached two ways:
 *  - Home / VPN: http://<private IP>:8096 (Wi-Fi at home, or Tailscale / Meshnet on the go)
 *  - Cloudflare: https://<tunnel hostname> behind Cloudflare Access (service token)
 * The token and Access keys live in CredentialStore (Keystore-encrypted) and only ever
 * travel as headers (SecureHeaderInterceptor). Songs and videos are saved with
 * jellyfin:// links and resolved to whichever address works right now.
 */
object HomeServer {

    private const val ID_BASE = 1L shl 41
    const val SCHEME = "jellyfin"

    /** Current working base URL (null = not connected). Main-thread reads are fine. */
    @Volatile var base: String? = null; private set
    @Volatile private var userId: String? = null
    @Volatile var dataSaver: Boolean = false; private set
    /** Chair ruling: original quality on Wi-Fi / VPN, Data saver by itself on mobile data. */
    @Volatile var autoSaver: Boolean = true; private set
    @Volatile var metered: Boolean = false
    val effectiveSaver: Boolean get() = dataSaver || (autoSaver && metered)

    private fun prefs(c: Context) = SecretPrefs(c, "audioverse_server", "audioverse_server_v1")

    private val plain = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val api: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(SecureHeaderInterceptor({ PlayerHolder.auth }))
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun trackId(itemId: String) = ID_BASE + (itemId.hashCode().toLong() and 0xffffffffL)

    fun isConfigured(c: Context): Boolean = prefs(c).get("server_id") != null && PlayerHolder.auth?.hasJellyfin == true

    fun setDataSaver(c: Context, on: Boolean) { dataSaver = on; prefs(c).put("saver", if (on) "1" else null) }
    fun setAutoSaver(c: Context, on: Boolean) { autoSaver = on; prefs(c).put("auto_saver", if (on) null else "0") }

    // ---------------- Trusted home Wi-Fi (U26) ----------------
    private fun settings(c: Context) = c.applicationContext.getSharedPreferences("audioverse_server_settings", Context.MODE_PRIVATE)
    fun loadTrust(c: Context) { com.jaygordonai.audioverse.network.PrivateTransportPolicy.trustHomeWifi = settings(c).getBoolean("trust_wifi", false) }
    fun setTrustHomeWifi(c: Context, on: Boolean) {
        settings(c).edit().putBoolean("trust_wifi", on).apply()
        com.jaygordonai.audioverse.network.PrivateTransportPolicy.trustHomeWifi = on
    }

    /** A plain-http home address may only be used while the transport policy allows it. */
    private fun homeUsable(home: String?): Boolean =
        home != null && (home.startsWith("https://") || com.jaygordonai.audioverse.network.PrivateTransportPolicy.plainHttpAllowed())

    // ---------------- Links ----------------

    /** jellyfin://audio/<id> or jellyfin://video/<id> → a stream URL on the current address (no token in it). */
    fun resolve(uri: Uri): Uri {
        if (uri.scheme != SCHEME) return uri
        val b = base ?: return uri
        val id = uri.lastPathSegment ?: return uri
        val dev = PlayerHolder.auth?.deviceId ?: "audioverse"
        val s = if (uri.host == "video") {
            if (effectiveSaver) "$b/Videos/$id/master.m3u8?MediaSourceId=$id&DeviceId=$dev&VideoCodec=h264&AudioCodec=aac&MaxStreamingBitrate=4000000&TranscodingMaxAudioChannels=2"
            else "$b/Videos/$id/stream?static=true"
        } else {
            if (effectiveSaver) "$b/Audio/$id/universal?UserId=${userId.orEmpty()}&DeviceId=$dev&Container=mp3&TranscodingContainer=mp3&TranscodingProtocol=http&AudioCodec=mp3&MaxStreamingBitrate=192000"
            else "$b/Audio/$id/stream?static=true"
        }
        return Uri.parse(s)
    }

    // ---------------- Connect ----------------

    private fun clean(url: String): String? {
        val u = url.trim().trimEnd('/')
        if (u.isEmpty()) return null
        val withScheme = if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
        return withScheme
    }

    private fun host(url: String) = Uri.parse(url).host.orEmpty()

    /** Signs in once with username + password; the password is not stored. Returns an error or null. */
    suspend fun connect(c: Context, home: String, remote: String, user: String, password: String, cfId: String, cfSecret: String): String? = withContext(Dispatchers.IO) {
        val homeUrl = clean(home)
        val remoteUrl = clean(remote)
        if (homeUrl == null && remoteUrl == null) return@withContext "Enter at least one address"
        if (remoteUrl != null && !remoteUrl.startsWith("https://")) return@withContext "The Cloudflare address must start with https://"
        if (homeUrl != null && homeUrl.startsWith("http://") && !com.jaygordonai.audioverse.network.PrivateNet.isPrivateAddress(host(homeUrl)))
            return@withContext "The home / VPN address must be a private IP like http://100.x.x.x:8096 or http://192.168.x.x:8096 (or use https)"

        val saved = PlayerHolder.auth
        val newCf = cfId.ifBlank { saved?.cfClientId }; val newSecret = cfSecret.ifBlank { saved?.cfClientSecret }
        // Credentials without a token yet, so the sign-in request carries only the Access keys.
        PlayerHolder.setAuth(c, AuthConfig(
            jellyfinHost = remoteUrl?.let(::host), tunnelHost = remoteUrl?.let(::host),
            cfClientId = newCf, cfClientSecret = newSecret, privateHost = homeUrl?.let(::host)
        ))
        if (remoteUrl == null && homeUrl != null && !homeUsable(homeUrl))
            return@withContext "To sign in over the home address, turn on your VPN, or switch on Trusted home Wi-Fi while on your home network"
        val base = pickBase(c, homeUrl, remoteUrl, expectId = null) ?: return@withContext "Can't reach the server on either address"
        val dev = PlayerHolder.auth?.deviceId ?: "audioverse"
        val req = Request.Builder().url("$base/Users/AuthenticateByName")
            .header("Authorization", "MediaBrowser Client=\"AudioVerse\", Device=\"Android\", DeviceId=\"$dev\", Version=\"0.12\"")
            // Emby reads its own header name for the same client details.
            .header("X-Emby-Authorization", "Emby Client=\"AudioVerse\", Device=\"Android\", DeviceId=\"$dev\", Version=\"0.12\"")
            .post(JSONObject().put("Username", user.trim()).put("Pw", password).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = try {
            api.newCall(req).execute().use { r ->
                if (r.code == 401) return@withContext "Wrong username or password"
                if (r.code == 403) return@withContext "Cloudflare Access refused the app: check the service token and that the policy is Service Auth"
                if (!r.isSuccessful) return@withContext "The server answered HTTP ${r.code}"
                JSONObject(r.body?.string() ?: "{}")
            }
        } catch (e: IOException) { return@withContext "Couldn't reach the server" }
        val token = body.optString("AccessToken").ifBlank { return@withContext "The server didn't return a login" }
        val uid = body.optJSONObject("User")?.optString("Id").orEmpty()
        val serverId = body.optString("ServerId")
        PlayerHolder.setAuth(c, PlayerHolder.auth!!.copy(jellyfinToken = token, jellyfinHost = remoteUrl?.let(::host) ?: homeUrl?.let(::host)))
        prefs(c).apply {
            put("home", homeUrl); put("remote", remoteUrl); put("user_id", uid); put("server_id", serverId)
        }
        null
    }

    /** Signs out: revokes the token on the server, then wipes everything saved here. */
    suspend fun disconnect(c: Context) = withContext(Dispatchers.IO) {
        base?.let { b -> try { api.newCall(Request.Builder().url("$b/Sessions/Logout").post(ByteArray(0).toRequestBody()).build()).execute().close() } catch (_: Exception) { } }
        base = null; userId = null
        prefs(c).clearAll()
        PlayerHolder.setAuth(c, null)
    }

    /**
     * Home / VPN address first (fast, free), Cloudflare second. The private address is only
     * used if the server there reports the same server ID we signed in to, so a stranger's
     * device at the same IP on some other Wi-Fi never receives the token.
     */
    private fun pickBase(c: Context, home: String?, remote: String?, expectId: String?): String? {
        fun probe(url: String, viaPlain: Boolean): String? = try {
            val client = if (viaPlain) plain else api
            client.newCall(Request.Builder().url("$url/System/Info/Public").build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string() ?: "{}").optString("Id")
            }
        } catch (e: Exception) { null }
        if (home != null && homeUsable(home)) {
            val id = probe(home, viaPlain = true) // no headers at all on the identity check
            if (id != null && (expectId == null || id == expectId)) return home
        }
        if (remote != null) {
            val id = probe(remote, viaPlain = false) // needs the Access keys
            if (id != null && (expectId == null || id == expectId)) return remote
        }
        return null
    }

    // ---------------- Library ----------------

    suspend fun load(c: Context, onProgress: (String) -> Unit): ServerLibrary? = withContext(Dispatchers.IO) {
        val p = prefs(c)
        val serverId = p.get("server_id") ?: return@withContext null
        dataSaver = p.get("saver") == "1"
        autoSaver = p.get("auto_saver") != "0"
        val home = p.get("home"); val remote = p.get("remote")
        val status = ServerStatus(configured = true, dataSaver = dataSaver, autoSaver = autoSaver,
            trustHomeWifi = com.jaygordonai.audioverse.network.PrivateTransportPolicy.trustHomeWifi, homeAddress = home.orEmpty(), remoteAddress = remote.orEmpty())
        if (PlayerHolder.auth?.hasJellyfin != true) return@withContext ServerLibrary(emptyList(), emptyList(), status.copy(error = "Signed out. Connect again."))
        onProgress("Looking for your home server…")
        val b = pickBase(c, home, remote, serverId)
        base = b; userId = p.get("user_id")
        if (b == null) return@withContext ServerLibrary(emptyList(), emptyList(), status.copy(error =
            if (home != null && !homeUsable(home) && remote == null) "Home address needs your VPN on (or Trusted home Wi-Fi switched on)"
            else "Server not reachable. At home: is the Mac Mini on? Away: is the VPN on, or is Cloudflare set up?"))
        val via = if (b == home) "home network / VPN" else "Cloudflare"
        try {
            val name = api.newCall(Request.Builder().url("$b/System/Info/Public").build()).execute().use { JSONObject(it.body?.string() ?: "{}").optString("ServerName") }
            val tracks = ArrayList<Track>()
            pages(b, "Audio", "Name,Album,AlbumArtist,Artists,RunTimeTicks,IndexNumber", "AlbumArtist,Album,ParentIndexNumber,IndexNumber") { o ->
                val id = o.getString("Id")
                tracks += Track(
                    id = trackId(id), uri = Uri.parse("$SCHEME://audio/$id"),
                    title = o.optString("Name").ifBlank { "Untitled" },
                    artist = o.optString("AlbumArtist").ifBlank { o.optJSONArray("Artists")?.optString(0).orEmpty() }.ifBlank { "Unknown artist" },
                    album = o.optString("Album"),
                    durationMs = o.optLong("RunTimeTicks") / 10_000,
                    trackNumber = o.optInt("IndexNumber"),
                    folder = "Home server"
                )
                if (tracks.size % 1000 == 0) onProgress("Home server · ${tracks.size} songs…")
            }
            val videos = ArrayList<ServerVideo>()
            pages(b, "Movie,Episode,Video,MusicVideo", "Name,SeriesName,ParentIndexNumber,IndexNumber,ProductionYear", "SeriesSortName,SortName,ParentIndexNumber,IndexNumber") { o ->
                val id = o.getString("Id")
                val series = o.optString("SeriesName")
                val sub = if (series.isNotBlank()) "$series · S${o.optInt("ParentIndexNumber")}E${o.optInt("IndexNumber")}"
                    else listOfNotNull(o.optString("Type").takeIf { it.isNotBlank() }, o.optInt("ProductionYear").takeIf { it > 0 }?.toString()).joinToString(" · ")
                videos += ServerVideo(Uri.parse("$SCHEME://video/$id"), o.optString("Name").ifBlank { "Untitled" }, sub)
            }
            ServerLibrary(tracks, videos, status.copy(serverName = name, via = via, songs = tracks.size, videos = videos.size))
        } catch (e: HttpFail) {
            ServerLibrary(emptyList(), emptyList(), status.copy(via = via, error =
                if (e.code == 401) "The server signed this phone out. Connect again." else "The server answered HTTP ${e.code}"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ServerLibrary(emptyList(), emptyList(), status.copy(via = via, error = "Lost the connection while loading"))
        }
    }

    private class HttpFail(val code: Int) : IOException()

    private fun pages(b: String, types: String, fields: String, sort: String, each: (JSONObject) -> Unit) {
        val uid = userId ?: throw HttpFail(401)
        var start = 0
        while (start < 60_000) {
            val url = "$b/Users/$uid/Items?IncludeItemTypes=$types&Recursive=true&Fields=$fields&SortBy=$sort" +
                "&StartIndex=$start&Limit=2000&EnableImages=false&EnableUserData=false&EnableTotalRecordCount=true"
            val o = api.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw HttpFail(r.code)
                JSONObject(r.body?.string() ?: "{}")
            }
            val items = o.optJSONArray("Items") ?: break
            for (i in 0 until items.length()) each(items.getJSONObject(i))
            start += items.length()
            if (items.length() < 2000 || start >= o.optInt("TotalRecordCount", start)) break
        }
    }
}
