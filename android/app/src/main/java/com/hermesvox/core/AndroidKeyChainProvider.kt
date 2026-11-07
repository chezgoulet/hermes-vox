package com.hermesvox.core

import android.content.Context
import android.security.KeyChain
import com.hermesvox.VoxLog
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Android KeyChain implementation of [MtlsKeyProvider].
 *
 * Calls [KeyChain.getPrivateKey] and [KeyChain.getCertificateChain].
 * These platform methods are blocking IPC calls and MUST only be invoked
 * from background threads (such as the JSSE TLS handshake worker).
 */
class AndroidKeyChainProvider(private val context: Context) : MtlsKeyProvider {
    override fun getPrivateKey(alias: String): PrivateKey? {
        return try {
            KeyChain.getPrivateKey(context, alias)
        } catch (e: Throwable) {
            VoxLog.w("event=mtls getPrivateKey error=${e.message?.take(120)}")
            null
        }
    }

    override fun getCertificateChain(alias: String): Array<X509Certificate>? {
        return try {
            KeyChain.getCertificateChain(context, alias)
        } catch (e: Throwable) {
            VoxLog.w("event=mtls getCertificateChain error=${e.message?.take(120)}")
            null
        }
    }
}
