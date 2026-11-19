package com.wanderwildwood.shoka.cloud

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A Nextcloud account as its login page hands it over: the server, the user's login name and an
 * app password made for this phone alone, which can be revoked from the server's security
 * settings without touching the real one. [user] (the login name) is what signs in; [uid] (the
 * user id the server knows, which can differ: an email address, another case) is what WebDAV
 * paths are built with. Until the server has been asked, the two are the same.
 */
data class Account(val server: String, val user: String, val password: String, val uid: String = user) {
    /** Without the password, so it cannot end up in a log. */
    override fun toString(): String = "Account(server=$server, user=$user, uid=$uid)"
}

/**
 * The server as the screens show it: without http:// or https://, without a default port (a
 * port of its own is kept) and without a slash at the end. The same for Home's account and the
 * app's own.
 */
fun shownServer(server: String): String =
    HomeAccount.sameServer(server).removePrefix("https://").removePrefix("http://")

/** The server could not be reached, or its certificate could not be trusted: nothing was sent. */
class Unreachable(message: String) : IOException(message)

/** The server would not let this account in: signed out on the server, or a wrong password. */
class Refused(message: String) : IOException(message)

internal const val AGENT = "Shelf (shoka)"

/**
 * One client for every Nextcloud call. The read timeout is long because a PUT of a video is
 * answered only once the server has written all of it.
 */
internal val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .build()

/**
 * Nextcloud's own sign-in, the one its apps use (Login Flow v2): the phone asks the server for a
 * login page, the reader signs in there in the browser, and the server hands this phone an app
 * password. Nobody types the account's password into this app, and two-factor works because it
 * is the server's own page. A phone with no browser takes an app password typed in instead.
 *
 * The same as Gallery's, each app with its own copy.
 */
object LoginFlow {

    data class Started(val loginUrl: String, val pollUrl: String, val token: String)

    fun start(server: String): Started {
        val request = Request.Builder()
            .url("${normalize(server)}/index.php/login/v2")
            .post(FormBody.Builder().build())
            .header("User-Agent", AGENT)
            .build()
        val json = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Refused("${response.code}")
                JSONObject(response.body!!.string())
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            throw Unreachable(e.message ?: "no answer")
        }
        val poll = json.getJSONObject("poll")
        return Started(json.getString("login"), poll.getString("endpoint"), poll.getString("token"))
    }

    /** The account, once the reader has finished in the browser, or null while they have not. */
    fun poll(started: Started): Account? {
        val request = Request.Builder()
            .url(started.pollUrl)
            .post(FormBody.Builder().add("token", started.token).build())
            .header("User-Agent", AGENT)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) throw Refused("${response.code}")
                val json = JSONObject(response.body!!.string())
                Account(
                    server = json.getString("server").trimEnd('/'),
                    user = json.getString("loginName"),
                    password = json.getString("appPassword"),
                )
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            null
        }
    }

    /**
     * The QR code Nextcloud shows beside a new app password ("Show QR code for mobile apps"):
     * `nc://login/user:<login>&password:<app password>&server:<address>`. Null for anything else.
     */
    fun fromQr(text: String): Account? {
        val t = text.trim()
        if (!t.startsWith("nc://login/")) return null
        val parts = Regex("(user|password|server):(.*?)(?=&(?:user|password|server):|$)")
            .findAll(t.removePrefix("nc://login/")).associate { it.groupValues[1] to it.groupValues[2] }
        val user = parts["user"]?.takeIf { it.isNotEmpty() } ?: return null
        val password = parts["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val server = parts["server"]?.takeIf { it.isNotEmpty() } ?: return null
        return Account(normalize(server), user, password)
    }

    /** What was typed, as an address: "cloud.example" becomes "https://cloud.example". */
    fun normalize(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /**
     * Plain http is refused for this app (Android blocks unencrypted traffic for it), so an
     * address typed with http:// is caught here and said, rather than failing as "unreachable".
     * Whether [base] (an address with its scheme) may be used: https always; plain http only in a
     * debug build ([debug]) and only to the computer an emulator runs on (10.0.2.2) or this phone
     * itself, the hosts its network config lets through for a test server. A release is https only.
     */
    fun allowed(base: String, debug: Boolean): Boolean {
        val t = base.trim()
        if (t.startsWith("https://", ignoreCase = true)) return true
        if (!debug || !t.startsWith("http://", ignoreCase = true)) return false
        val host = t.substring(7).substringBefore('/').substringBefore(':').lowercase()
        return host in TEST_HOSTS
    }

    private val TEST_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /**
     * The account with the user id the server knows for it ([Account.uid]), asked once from
     * `ocs/v2.php/cloud/user`. Login Flow hands over only the login name, which may be an email
     * address; WebDAV paths need the user id. Unchanged when the server does not say.
     */
    fun withUid(account: Account): Account = runCatching {
        val request = Request.Builder()
            .url("${account.server.trimEnd('/')}/ocs/v2.php/cloud/user?format=json")
            .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
            .header("OCS-APIRequest", "true")
            .header("User-Agent", AGENT)
            .build()
        http.newCall(request).execute().use { r ->
            NextcloudCheck.userId(r.code, r.body?.string().orEmpty())
        }
    }.getOrNull()?.let { account.copy(uid = it) } ?: account

    /** What asking the server whether a user name and app password are good came back as. */
    enum class Check { GOOD, REFUSED, UNREACHABLE }

    /** Asks the server who these credentials belong to, once, before they are kept. */
    fun verify(account: Account): Check = try {
        val request = Request.Builder()
            .url("${account.server.trimEnd('/')}/ocs/v2.php/cloud/user")
            .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
            .header("OCS-APIRequest", "true")
            .header("User-Agent", AGENT)
            .build()
        http.newCall(request).execute().use {
            when {
                it.isSuccessful -> Check.GOOD
                it.code == 401 || it.code == 403 || it.code == 997 -> Check.REFUSED
                else -> Check.UNREACHABLE
            }
        }
    } catch (e: java.io.IOException) {
        Check.UNREACHABLE
    }

    /**
     * Signing out on the server too: the app password is deleted there, so it opens nothing even
     * if a copy of it survived somewhere. Best effort: off the network, the reader is still
     * signed out here, and the password can be revoked from the server's own security settings.
     */
    fun revoke(account: Account): Boolean = runCatching {
        val request = Request.Builder()
            .url("${account.server.trimEnd('/')}/ocs/v2.php/core/apppassword")
            .delete()
            .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
            .header("OCS-APIRequest", "true")
            .header("User-Agent", AGENT)
            .build()
        http.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
