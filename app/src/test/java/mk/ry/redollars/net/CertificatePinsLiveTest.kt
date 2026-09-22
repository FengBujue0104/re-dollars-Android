package mk.ry.redollars.net

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException

class CertificatePinsLiveTest {
    private val currentRyPins = listOf(
        "sha256//V45t/4JCO3Mf5A78GtIGCNx92UiH5y1cBBuTCo0dxE=",
        "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=",
    )

    private fun client(hosts: List<String>, pins: List<String>): OkHttpClient {
        val builder = CertificatePinner.Builder()
        hosts.forEach { host -> pins.forEach { pin -> builder.add(host, pin) } }
        return OkHttpClient.Builder().certificatePinner(builder.build()).build()
    }

    @Test
    fun currentPinsConnectAndOldPinsFail() {
        val hosts = listOf("rd.ry.mk", "auth.ry.mk", "up.ry.mk")
        val current = client(hosts, currentRyPins)
        hosts.forEach { host ->
            current.newCall(Request.Builder().url("https://$host/").build()).execute().use { response ->
                println("CURRENT $host HTTPS ${response.code}")
                assertTrue(response.code in 100..599)
            }
        }

        val obsoletePins = mapOf(
            "rd.ry.mk" to listOf(
                "sha256/Nna/qm7tawbg1k2+WPynsaxzTVl+fpU2LLouasFdqP8=",
                "sha256/nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E=",
            ),
            "up.ry.mk" to listOf(
                "sha256/Nna/qm7tawbg1k2+WPynsaxzTVl+fpU2LLouasFdqP8=",
                "sha256/nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E=",
            ),
            "auth.ry.mk" to listOf(
                "sha256/lOsdd9qcRNoS+JkYZYpo5QwEoW4smzwhvpAPYORVkDk=",
                "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
            ),
        )
        obsoletePins.forEach { (host, pins) ->
            try {
                client(listOf(host), pins)
                    .newCall(Request.Builder().url("https://$host/").build())
                    .execute()
                    .use { fail("obsolete pins unexpectedly accepted $host with HTTP ${it.code}") }
            } catch (expected: SSLPeerUnverifiedException) {
                assertTrue(expected.message.orEmpty().contains("Certificate pinning failure"))
                println("OBSOLETE $host FAIL ${expected.message?.lineSequence()?.first()}")
            }
        }
    }

    @Test
    fun websocketOpensWithCurrentPins() {
        val opened = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val ws = client(listOf("rd.ry.mk"), currentRyPins).newWebSocket(
            Request.Builder().url("wss://rd.ry.mk/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    println("WEBSOCKET rd.ry.mk OPEN HTTP ${response.code}")
                    opened.countDown()
                    webSocket.close(1000, "pin smoke complete")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.set(t)
                    opened.countDown()
                }
            },
        )
        assertTrue("WebSocket did not open in 20 seconds", opened.await(20, TimeUnit.SECONDS))
        ws.cancel()
        assertNull("WebSocket failed: ${failure.get()}", failure.get())
    }
}
