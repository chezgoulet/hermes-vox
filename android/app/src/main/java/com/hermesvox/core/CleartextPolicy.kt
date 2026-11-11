package com.hermesvox.core

import java.net.URI

/**
 * M4 (0.9.1 review): a plain-HTTP endpoint is trusted only while the tailnet is up.
 *
 * `network_security_config.xml` permits cleartext to `*.ts.net` because MagicDNS is
 * tailnet-private *by construction*. With Tailscale down that construction is gone: the
 * name is resolved by whatever network the phone is on, and a hostile answer collects the
 * bearer key on the first request. The XML cannot ask "is the tunnel up?", so the app
 * refuses the request here — the same [com.hermesvox.Tailnet] address check that decides
 * whether the phone can be reached at all.
 *
 * The rule is deliberately narrow:
 *  * `https://` is never touched — TLS is what provides the guarantee;
 *  * loopback is never touched — `127.0.0.1` / `localhost` cannot be answered off-device;
 *  * every other cleartext URL is refused while [tunnelUp] is false.
 *
 * [tunnelUp] is injected (VoxApplication wires it to Tailnet) so the rule is pure and the
 * whole matrix is proven off-device in `CleartextPolicyTest`.
 *
 * Residual risk (#413 second pass, recorded rather than closed):
 *  * "Tunnel up" is `Tailnet.address() != null`: some non-radio interface holds a
 *    100.64.0.0/10 or fd7a:115c:a1e0::/48 address. That is a heuristic, not a check of
 *    Tailscale's own tunnel. A VPN that also numbers its tun from CGNAT (Cloudflare WARP)
 *    would read as up, so `http://` to a `*.ts.net` name would be allowed with Tailscale
 *    down. This is the open #413 L8 question, unverified on a WARP device.
 *  * The ts.net domain-config still trusts user-installed CAs for `*.ts.net`.
 *  * LAN policy is unchanged by M4 and deliberately not touched here: the network
 *    security config's base-config already refuses cleartext to every non-ts.net host.
 * `https://` via `tailscale serve` with a system-trusted certificate removes the first two.
 */
object CleartextPolicy {

    /** The line a refusal shows. It names the cause and the way out, never the key. */
    const val REFUSAL =
        "Tailscale is not connected, so this plain-HTTP endpoint cannot be trusted. " +
            "Connect the tunnel, or use its https://<machine>.<tailnet>.ts.net address."

    /** The app's live detector. False until VoxApplication installs [Tailnet]; fail-closed. */
    @Volatile var tunnelUp: () -> Boolean = { false }

    /** The refusal for [url], or null when it may proceed. */
    fun refusal(url: String, tunnelUp: Boolean = this.tunnelUp()): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return null
        if (isLoopback(trimmed)) return null
        return if (tunnelUp) null else REFUSAL
    }

    /** `localhost`, `127.0.0.0/8`, `::1` and the wildcard are on-device by definition. */
    fun isLoopback(url: String): Boolean {
        val host = try { URI(url.trim()).host?.lowercase() } catch (_: Exception) { null } ?: return false
        if (host == "localhost" || host == "[::1]" || host == "::1" || host == "0.0.0.0") return true
        val v4 = host.removePrefix("[").removeSuffix("]")
        return v4.startsWith("127.") && v4.split('.').size == 4
    }
}
