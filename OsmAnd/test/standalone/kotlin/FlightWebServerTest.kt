package net.osmand.test.junit

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import net.osmand.plus.plugins.flightmode.FlightWebServer
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic payloads and loopback requests; device interfaces are never enumerated. */
class FlightWebServerTest {
  private fun request(server: FlightWebServer, path: String, method: String = "GET"): ByteArray =
      Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
        socket.soTimeout = 5_000
        socket
            .getOutputStream()
            .write("$method $path HTTP/1.1\r\nHost: osmand.localhost\r\n\r\n".toByteArray())
        socket.getInputStream().readBytes()
      }

  @Test
  fun servesOfflinePageAndCurrentUtf8Snapshot() {
    FlightWebServer { listOf("192.0.2.7") }
        .use { server ->
          val page = request(server, server.path).toString(Charsets.UTF_8)
          assertTrue(page.startsWith("HTTP/1.1 200 OK"))
          assertTrue(page.contains("text/html"))
          assertTrue(page.contains("setTimeout(refresh,2000)"))
          assertFalse(page.contains("src=\"https://"))
          server.snapshot = "{\"title\":\"Été <script>\",\"rows\":[]}"
          val response = request(server, "${server.path}/data")
          val text = response.toString(Charsets.UTF_8)
          assertTrue(text.endsWith(server.snapshot))
          assertTrue(
              text.contains(
                  "Content-Length: ${server.snapshot.toByteArray(Charsets.UTF_8).size}\r\n"))
          assertTrue(text.contains("Cache-Control: no-store"))
          server.snapshot = "{\"title\":\"next\"}"
          assertTrue(
              request(server, "${server.path}/data")
                  .toString(Charsets.UTF_8)
                  .endsWith(server.snapshot))
        }
  }

  @Test
  fun unknownLinksAndWritesDoNotExposeTheSnapshot() {
    FlightWebServer { emptyList() }
        .use { server ->
          server.snapshot = "synthetic-private-value"
          for (path in listOf("/", "/data", "/wrong/data", "/${server.path}/data")) {
            val response = request(server, path).toString(Charsets.UTF_8)
            assertTrue(response.startsWith("HTTP/1.1 404"))
            assertFalse(response.contains(server.snapshot))
          }
          assertTrue(
              request(server, "${server.path}/data", "POST")
                  .toString(Charsets.UTF_8)
                  .startsWith("HTTP/1.1 405"))
          assertEquals("synthetic-private-value", server.snapshot)
        }
  }

  @Test
  fun exposesListeningPortAndSyntheticInterfaceAddresses() {
    FlightWebServer { listOf("192.0.2.7", "198.51.100.9", "2001:db8::8", "192.0.2.7") }
        .use { server ->
          assertTrue(server.port in 58740..58749)
          assertEquals(
              listOf("osmand.localhost", "192.0.2.7", "198.51.100.9", "[2001:db8::8]").map {
                "http://$it:${server.port}${server.path}"
              },
              server.urls())
        }
  }

  @Test
  fun advancesPastBusyPortAndReleasesListenerOnClose() {
    ServerSocket()
        .apply {
          reuseAddress = true
          bind(InetSocketAddress(58740))
        }
        .use {
          val server = FlightWebServer { emptyList() }
          val port = server.port
          try {
            assertTrue(port in 58741..58749)
          } finally {
            server.close()
          }
          ServerSocket()
              .apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
              }
              .close()
        }
  }

  @Test
  fun handlesMalformedRequestWithoutKillingTheListener() {
    FlightWebServer { emptyList() }
        .use { server ->
          assertTrue(request(server, "").toString(Charsets.UTF_8).startsWith("HTTP/1.1 404"))
          assertTrue(
              request(server, server.path).toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
        }
  }
}
