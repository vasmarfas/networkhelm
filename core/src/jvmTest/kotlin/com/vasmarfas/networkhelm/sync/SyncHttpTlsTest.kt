package com.vasmarfas.networkhelm.sync

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.transport.MemoryTrustStore
import com.vasmarfas.networkhelm.transport.TrustKind
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.transport.TrustRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SyncHttpTlsTest {

    // a server of this machine with a self-signed certificate that names localhost, reached by 127.0.0.1
    private fun server(keystore: String): HttpsServer {
        val keys = KeyStore.getInstance("PKCS12").apply { load(Base64.getDecoder().decode(keystore).inputStream(), "test".toCharArray()) }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test".toCharArray()) }.keyManagers
        return HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply { init(managers, null, null) })
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
            start()
        }
    }

    @Test
    fun theStoreOfTheSystemRefusesASelfSignedServerThatIsAskedAboutAndThenPinnedWhateverItsCertificateNames() = runBlocking {
        val store = MemoryTrustStore()
        val asked = mutableListOf<TrustRequest>()
        val first = server(ONE)
        val second = server(TWO)
        val http = SyncHttp(TrustPolicy(store) {
            asked += it
            true
        })
        val refusing = SyncHttp(TrustPolicy(store) {
            asked += it
            false
        })
        try {
            val url = "https://127.0.0.1:${first.address.port}/dav/"
            assertEquals("ok", http.send("GET", url).body.decodeToString())
            assertEquals(ONE_SHA256, asked.single().fingerprint)
            assertNull(asked.single().previous)
            assertEquals("ok", http.send("GET", url).body.decodeToString())
            assertEquals(1, asked.size)

            // another certificate where one was pinned is asked about with the one before, and a refusal stops the request
            store.put("127.0.0.1", second.address.port, TrustKind.Tls, ONE_SHA256)
            val error = assertFailsWith<DeviceException> { refusing.send("GET", "https://127.0.0.1:${second.address.port}/") }
            assertEquals("127.0.0.1: $TWO_SHA256 was not accepted", error.message)
            assertEquals(ONE_SHA256, asked.last().previous)
        } finally {
            http.close()
            refusing.close()
            first.stop(0)
            second.stop(0)
        }
    }

    @Test
    fun aBodyOverItsLimitIsRefused() = runBlocking {
        val http = SyncHttp(TrustPolicy(MemoryTrustStore()) { false }) { HttpClient(MockEngine { respond(ByteReadChannel(ByteArray(64 * 1024 * 1024 + 1))) }) }
        assertEquals("dav.example.org sent more than 64 MiB", assertFailsWith<DeviceException> { http.send("GET", "https://dav.example.org/") }.message)
    }
}

// two self-signed certificates of P-256 for localhost, valid to 2125, in PKCS12 with the password test
private const val ONE =
    "MIIEOQIBAzCCA+8GCSqGSIb3DQEHAaCCA+AEggPcMIID2DCCAmoGCSqGSIb3DQEHBqCCAlswggJXAgEAMIICUAYJKoZIhvcNAQcBMF8GCSqGSIb3DQEFDTBS" +
    "MDEGCSqGSIb3DQEFDDAkBBCigWiOmlK3TO6fS8JKN0riAgJOIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQSskUn73Iik5UADKr1i9l0ICCAeBSnH7i" +
    "wy1PLcYJC2ItZQ3Bm6JGDs8lGm6RBeP4WL0v45qGIN5XTOeYxgFL9PcXjKX5fOzFVolqlwA05QRbgzgdntSJbCHDmJAC7FBnwxcYC5iDzYFFjZMyG4tlDb1Z" +
    "hZh3FPJikR5kMZ0LCGUNkKEuH9leF1seqk6Bqsvj+hfWJO9x6FNwn2m5fNuFmta2EVQ3/UfvaafnjWSOZvcGXN3NwW+W7TxzaiB/M9HjjNgi4IMsY0I0gNPp" +
    "wvNOMJYpNuZExM5Pge2Rgt0Ae6ZTWG473uDBdurL6bQ5nHObXWhmtSExKFs2Iu798ikNEUV5xECYPss160PdysRA9j/fPKjlSgDEqy7YFkEpRjNQBBRewC++" +
    "PEegjqnBTqlIxw21d54D6UfOhq/o6FcMWMAAWoefX75GuTs+F87LYpmCsEfbiAiBPFXHk66/dMQbSgpueq9+LsxMKWS+j4onN2bMYcClNZrLw4IDQZqlLIDZ" +
    "KumAgcflTEvw27wL/PJ6N+jAXPwHbxNUoUK8XaCjvcYaaisHMYt/CmsvJiYc4/A2qW+MtKtCzqzcnpiE+qHwyRKPmiuLNqCOSKWnlrIgL+X5nlzBu4q+wY+R" +
    "R2XYdfO/cedDfAO6hCkmkrhi3e49v6NfUA8wggFmBgkqhkiG9w0BBwGgggFXBIIBUzCCAU8wggFLBgsqhkiG9w0BDAoBAqCB9zCB9DBfBgkqhkiG9w0BBQ0w" +
    "UjAxBgkqhkiG9w0BBQwwJAQQwK7km2bwS6mG1ce+zXLfZgICTiAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEBOS98Pv0Ym+/0q/1kUji6IEgZAotYF1" +
    "EYjvAF1PgCMxgZWh2KFyBQbJw+sYV0MH09RGmEnme6oEnWJDZ8/IHq+euKeLx9A1CA7dMx1hX4byV0421eGdea/482cPt4goudSmUgytJSdTXQdWFRqObNDb" +
    "xPZ0ZoAYurPEkviJDgytSk+PYTEF3HLgtOg2kk8iOL7/WhZi3c07Gkyj/jDO93L4aL8xQjAbBgkqhkiG9w0BCRQxDh4MAHMAZQByAHYAZQByMCMGCSqGSIb3" +
    "DQEJFTEWBBQkCsFh8jxGSghXPSJWzdD6kE9+GzBBMDEwDQYJYIZIAWUDBAIBBQAEINTSkfdG/1IPZFFUFvXCjh+56rJnWbY+uygur7NlfzSPBAioiYWuRGXo" +
    "NQICCAA="

private const val ONE_SHA256 = "9cb78dff3b2138d5f31bf10bed2a819e4e03cf0e82251a1699e83e1118cddc60"

private const val TWO =
    "MIIEOQIBAzCCA+8GCSqGSIb3DQEHAaCCA+AEggPcMIID2DCCAmoGCSqGSIb3DQEHBqCCAlswggJXAgEAMIICUAYJKoZIhvcNAQcBMF8GCSqGSIb3DQEFDTBS" +
    "MDEGCSqGSIb3DQEFDDAkBBCcllO15/sM2y7U+0YtY92CAgJOIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQ56iQega5krNG967b7ON6PICCAeDVl7XX" +
    "543GJP5xw2YI1eeS5m4hoslrvSURqD7/WUW4A8LMQ8zkiBm1nCOCP0VLH1JhUD3N4cfOa1F6qwvn8rAv2hUUIqRZkKfngNngVD152wgLMtoyf3zzeDuyjO0A" +
    "Cao1aqQd8Rn74TcdK6G71lMdxOTPzdO/TWB74d7HjYaBqY+nLn0mPI1MtBSX/AbKhE1HGVZfwWkRrXek1GSyejve3xZhb3UEpVIK9v9mjz7iyEYYLdB/MqEX" +
    "OMLf8zTt+eBcMM9Xd5GDXtxZsKdrS3klNqRXfHDGJ5+kNUaRfbTVf/K1PTHadcFg/wHz8U/MTvMDbfh93fewCz+h5uh6rlilvdC6BrMV5XlMWL1pjP7UA5KH" +
    "hL5i7Lk5C+zRDbb/h6qBSneEG/4YjUz2aGiry/XPOe+jWzY2cTc4yBS1NXizOzLs5nByn53XMz1lJ6w3QkwU0cpQo8xnXIS9ZMvu6oaYaMnCIsNorPOnAUhT" +
    "9RK1ZaUW04OmHAoDEmOnudgU3ScPrXlrbb6YNjC9LfFp6zPKPtgHvVaEcttAAshB0zj2PYx4ea0zAPkqL/pv3BDRQpJl1uDv9l30G0Ka0VjhoZsndZjCHsoB" +
    "bQWEpeYWtpYjxl13z3bTVW9skd3wPWsPAiEwggFmBgkqhkiG9w0BBwGgggFXBIIBUzCCAU8wggFLBgsqhkiG9w0BDAoBAqCB9zCB9DBfBgkqhkiG9w0BBQ0w" +
    "UjAxBgkqhkiG9w0BBQwwJAQQjnud40fvyLhQT12uK6zaLgICTiAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEAOuNkaOfwVmswDWGuKXbv4EgZDCNJk3" +
    "GrKhn8JTeP7A+WT86aiKkVVSL+jlxm5T78fshacMCx37hlTQymuFmeArytUh5yfPRnFdDmLEW4ilJqJN671E9JpF/MGEamKQv35NJMMgSrn2up3uFb+UrU6a" +
    "dYMZCRk1o2IdT3AVjkYSd3hjQtEjsg6rOuVfSdVH/7w310UcpU9bVIGGcsDtgX+jgukxQjAbBgkqhkiG9w0BCRQxDh4MAHMAZQByAHYAZQByMCMGCSqGSIb3" +
    "DQEJFTEWBBQUra1kckPeYAd4c1L7JVTD2E048TBBMDEwDQYJYIZIAWUDBAIBBQAEIGbSNR4uOCghXXzhGRf8ccWsuwm1FsXbDeS5KgsqnpFuBAhq789FBgwB" +
    "QwICCAA="

private const val TWO_SHA256 = "44710413ea30a62b72ef903951460463bab45d5403494eedead155a2b1471f43"
