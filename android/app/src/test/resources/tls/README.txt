PUBLIC TEST FIXTURES — throwaway keys, nothing here is a real credential.
Every keystore's password is: test-only

They live under src/test/resources, are read only by unit tests, and never reach an APK:
scripts/gate.sh inspects the built APK and fails if any key material is packaged. (An
earlier version of this file described a MockWebServer-based test that never existed.)

client-identities.p12   — client identities, used by TestKeyProvider (the JVM stand-in for
                          Android KeyChain), MtlsHandshakeTest and MtlsEntityTest.
                            server          CN=localhost, SAN dns:localhost + ip:127.0.0.1,
                                            EKU serverAuth, self-signed (used as a server by
                                            MtlsHandshakeTest only)
                            first, second   CN=first-client / second-client, EKU clientAuth,
                                            self-signed
                          RSA 2048, valid 2020-01-01 through 2120 (JDK 21 keytool).

server-trust.p12        — a CA-issued server chain, used by MtlsEntityTest so the client's
                          PLATFORM trust manager has a real chain to verify:
                            system   leaf CN=localhost, SAN dns:localhost + ip:127.0.0.1,
                                     EKU serverAuth, issued by `system-ca`
                          It also carries user/unknown/expired leaves and their roots
                          (user-ca, unknown-ca); no test uses those today. RSA 2048/SHA-256,
                          valid 2020-01-01 through 2120.

jvm-truststore.p12      — the JVM's stand-in for Android's system trust store: one entry,
                          `system-ca`. build.gradle installs it as javax.net.ssl.trustStore
                          for the unit-test JVM. The production path calls
                          SSLContext.init(km, null, null); the null TrustManager is Android's
                          platform trust manager, and on the JVM it is the JDK default store.
                          Pointing that store at this file is what lets MtlsEntityTest prove
                          the real path end to end without changing it.

Generated with Python cryptography 50 (RSA 2048/SHA-256 X.509 + PKCS12) and JDK 21 keytool.
