package com.hermesvox

import com.hermesvox.core.Entity

/**
 * SessionsClient — the two GETs behind the session card (#437), and nothing else.
 *
 * Every decision about what an answer *means* lives in [Sessions]; this file only puts a
 * request on the wire and hands back the status and the body. That split is deliberate: the
 * card's lines, the ordering and the merge are proven off-device, and the one thing left here
 * is the URL — which is the thing this feature has to get right.
 *
 * **The host, not the profile.** [Sessions.rootBase] strips a profile's `/p/<name>` suffix, so
 * a phone talking to `https://host/p/home` asks `https://host/api/sessions`. The session list
 * is a property of the gateway: on `mikoa` the root answers 200 for the same key that a
 * profile path answers 401 for (#436), so asking at the profile would make the feature look
 * broken on exactly the installs that use profiles.
 *
 * **The key, and no scope.** The bearer is the one the profile already holds. The declared
 * entity scope (`X-Hermes-Session-Key`) is deliberately not sent: it names a conversation's
 * long-term memory, and listing sessions is not a conversation. Sending it would make this
 * request look like a turn to the gateway's own accounting.
 */
object SessionsClient {

    /** One answer: the status (or -1 for no answer at all) and the body. */
    data class Fetched(val code: Int, val body: String = "")

    /** A list body is JSON; anything past this is not one, and the card says so. */
    const val BODY_MAX = 512 * 1024

    /** One page of the host's session list. */
    fun list(profileBase: String, key: String, limit: Int = Sessions.LIMIT, offset: Int = 0): Fetched =
        get(profileBase, key, Sessions.listPath(limit, offset))

    /** One session's transcript. */
    fun messages(profileBase: String, key: String, id: String): Fetched =
        get(profileBase, key, Sessions.messagesPath(id))

    /**
     * One GET at the host. A transport failure, a timeout and the cleartext refusal are all
     * -1 — the caller's [Sessions.NoAnswer] — because the card has one line for "the host did
     * not answer" and it is not helped by the exception's class name.
     */
    private fun get(profileBase: String, key: String, path: String): Fetched = try {
        val url = Entity.url(Sessions.rootBase(profileBase), path)
        val c = Entity.open("GET", url, Entity.headers(key, ""), Sessions.TIMEOUT_MS, Sessions.TIMEOUT_MS)
        Entity.exchange(c, Sessions.TIMEOUT_MS.toLong(), "hermes sessions") {
            val code = c.responseCode
            val body = Entity.body(c)?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
                .take(BODY_MAX)
            Fetched(code, body)
        }
    } catch (_: Throwable) {
        Fetched(-1)
    }
}
