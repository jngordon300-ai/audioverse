package com.jaygordonai.audioverse.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens a web link in the phone's own app for it (YouTube, iHeart, Spotify, browser). Web links only. */
fun openExternal(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" && uri.scheme != "http") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open that link", Toast.LENGTH_SHORT).show()
    }
}
