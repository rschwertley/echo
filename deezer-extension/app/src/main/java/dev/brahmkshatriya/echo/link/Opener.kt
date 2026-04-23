package dev.brahmkshatriya.echo.link

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Opener : Activity() {

    private val extensionId = "deezerApp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data

        if (uri != null) {
            Thread {
                val resolvedUri = resolveRedirects(uri.toString())
                runOnUiThread {
                    if (resolvedUri != null) {
                        processUri(Uri.parse(resolvedUri))
                    } else {
                        finishAndRemoveTask()
                    }
                }
            }.start()
        }
    }

    private fun resolveRedirects(urlString: String): String? {
        var url = urlString
        var redirectUrl: String?

        try {
            while (true) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    redirectUrl = connection.getHeaderField("Location")
                    if (redirectUrl != null) {
                        url = if (Uri.parse(redirectUrl).isRelative) {
                            URL(URL(url), redirectUrl).toString()
                        } else {
                            redirectUrl
                        }
                    } else {
                        break
                    }
                } else {
                    redirectUrl = url
                    break
                }
                connection.disconnect()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }

        return redirectUrl
    }

    private fun processUri(uri: Uri) {
        val type: String
        val segment: Int
        if (!uri.pathSegments[0].contains("album") ||
            !uri.pathSegments[0].contains("artist") ||
            !uri.pathSegments[0].contains("playlist") ||
            !uri.pathSegments[0].contains("track")
        ) {
            type = uri.pathSegments[1]
            segment = 2
        } else {
            type = uri.pathSegments[0]
            segment = 1
        }

        val path = when (type) {
            "artist" -> {
                val artistId = uri.pathSegments[segment] ?: return
                "artist/$artistId"
            }

            "playlist" -> {
                val playlistId = uri.pathSegments[segment] ?: return
                "playlist/$playlistId"
            }

            "album" -> {
                val albumId = uri.pathSegments[segment] ?: return
                "album/$albumId"
            }

            "track" -> {
                val trackId = uri.pathSegments[segment] ?: return
                "track/$trackId"
            }

            else -> return
        }

        val uriString = "echo://music/$extensionId/$path"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriString)))
        finishAndRemoveTask()
    }
}