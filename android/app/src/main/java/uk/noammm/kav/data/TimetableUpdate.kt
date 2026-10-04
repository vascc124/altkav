package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// Kav+: the timetable in the APK is one week of the MOT feed. A GitHub Action on this fork rebuilds it
// every Saturday night (.github/workflows/timetable.yml) onto the "timetable" pre-release; this fetches
// a newer one, at most once a day, and loadNet() uses it from the next start.
object TimetableUpdate {
    private const val BASE = "https://github.com/${Updates.OWNER}/${Updates.REPO}/releases/download/timetable/"
    private const val DAY_MS = 24 * 3600_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kavplus", Context.MODE_PRIVATE)
    fun file(ctx: Context) = File(File(ctx.filesDir, "timetable"), "il.kav.gz")

    // When the timetable in use was built: the downloaded one's date, else the APK's own bundle's.
    fun inUseSince(ctx: Context): Long =
        maxOf(prefs(ctx).getLong("builtMs", 0L).takeIf { file(ctx).exists() } ?: 0L, uk.noammm.kav.BuildConfig.TIMETABLE_BUILT_MS)

    // A downloaded timetable that can't be read is dropped, and the APK's is used again.
    fun discard(ctx: Context) {
        file(ctx).delete()
        prefs(ctx).edit().remove("builtMs").apply()
    }

    /** Checks for a newer weekly timetable and downloads it. True when one was saved for the next start. */
    fun check(ctx: Context, force: Boolean = false): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("checkedMs", 0L) < DAY_MS) return false
        p.edit().putLong("checkedMs", now).apply()

        val meta = JSONObject(get(BASE + "timetable.json").decodeToString())
        val built = runCatching { java.time.Instant.parse(meta.getString("built")).toEpochMilli() }.getOrNull() ?: return false
        if (built <= inUseSince(ctx)) return false
        val bytes = meta.getLong("bytes")
        val sha = meta.getString("sha256").lowercase()

        val data = get(BASE + "il.kav.gz")
        if (data.size.toLong() != bytes) throw java.io.IOException("timetable size ${data.size} != $bytes")
        val got = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        if (got != sha) throw java.io.IOException("timetable checksum mismatch")
        // Must parse before it replaces anything.
        Net.read(data.inputStream())

        val dest = file(ctx)
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        part.writeBytes(data)
        if (!part.renameTo(dest)) { dest.delete(); if (!part.renameTo(dest)) throw java.io.IOException("could not keep the timetable") }
        p.edit().putLong("builtMs", built).apply()
        return true
    }

    private fun get(url: String): ByteArray {
        var target = URL(url)
        repeat(5) {
            val c = (target.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "KavPlus")
            }
            when (c.responseCode) {
                in 300..399 -> { target = URL(target, c.getHeaderField("Location") ?: throw java.io.IOException("redirect without Location")); c.disconnect() }
                200 -> return c.inputStream.use { it.readBytes() }
                else -> throw java.io.IOException("timetable HTTP ${c.responseCode}")
            }
        }
        throw java.io.IOException("too many redirects")
    }
}
