package cloud.trotter.log.gig.core.data.feed

import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * An HTTPS fake server that answers for the real feed host names. The client resolves every host to loopback and
 * connects to the server's port, and trusts only the server's test certificate, so a test fetches
 * `https://gig.log.trotter.cloud/feeds/...` exactly as production builds it, with TLS on.
 */
class FeedTestServer : AutoCloseable {
    private val certificate = HeldCertificate.Builder()
        .addSubjectAlternativeName(GIG_LOG_HOST)
        .addSubjectAlternativeName(CUSTOM_HOST)
        .build()

    val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        start()
    }

    val client: OkHttpClient = run {
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val port = server.port
        OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .dns { listOf(InetAddress.getLoopbackAddress()) }
            .socketFactory(LoopbackSocketFactory(port))
            .build()
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
    }

    /** Opens every socket to the server's port, whatever port the URL names. */
    private class LoopbackSocketFactory(private val serverPort: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                // serverPort, not Socket.port: inside this object `port` is the socket's own (unset) port.
                super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), timeout)
            }
        }

        override fun createSocket(host: String?, port: Int): Socket = unsupported()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()
        override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("connect through createSocket()")
    }

    companion object {
        const val GIG_LOG_HOST = "gig.log.trotter.cloud"
        const val CUSTOM_HOST = "feeds.example.org"
        const val CUSTOM_FEEDS_URL = "https://$CUSTOM_HOST/feeds/"
    }
}
