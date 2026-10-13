package net.osmand.plus.plugins.flightmode

import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID

/** Small, read-only local viewer. The unpredictable link is required for every request. */
internal class FlightWebServer(
    private val addresses: () -> List<String> = {
      NetworkInterface.getNetworkInterfaces()
          ?.let { Collections.list(it) }
          .orEmpty()
          .filter { it.isUp }
          .flatMap { Collections.list(it.inetAddresses) }
          .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
          .mapNotNull { it.hostAddress }
    },
) : AutoCloseable {
  private val listener =
      (58740..58749).firstNotNullOfOrNull { port ->
        val socket = ServerSocket()
        try {
          socket.reuseAddress = true
          socket.bind(InetSocketAddress(port), 8)
          socket
        } catch (_: IOException) {
          socket.close()
          null
        }
      } ?: throw IOException("No available listening port (58740–58749)")
  val port: Int
    get() = listener.localPort

  val path = "/${UUID.randomUUID()}"
  @Volatile var snapshot = "{}"
  @Volatile private var client: Socket? = null

  init {
    Thread(
            {
              while (!listener.isClosed) {
                val socket =
                    try {
                      listener.accept()
                    } catch (_: IOException) {
                      break
                    }
                client = socket
                socket.use {
                  if (!listener.isClosed)
                      try {
                        respond(it)
                      } catch (_: IOException) {}
                }
                client = null
              }
            },
            "FlightWeb")
        .apply {
          isDaemon = true
          start()
        }
  }

  fun urls(): List<String> =
      (listOf("osmand.localhost") + addresses()).distinct().map {
        "http://${if (':' in it) "[$it]" else it}:$port$path"
      }

  private fun respond(socket: Socket) {
    socket.soTimeout = 3_000
    val input = socket.getInputStream().buffered()
    val line = StringBuilder()
    while (line.length < 2_048) {
      val char = input.read()
      if (char < 0 || char == 10) break
      line.append(char.toChar())
    }
    val request = line.toString().trim().split(' ', limit = 3)
    val target = request.getOrNull(1)
    val (status, type, body) =
        when {
          target != path && target != "$path/data" ->
              Triple("404 Not Found", "text/plain", "Not found")
          request.firstOrNull() != "GET" ->
              Triple("405 Method Not Allowed", "text/plain", "GET only")
          target == path -> Triple("200 OK", "text/html", PAGE)
          else -> Triple("200 OK", "application/json", snapshot)
        }
    val bytes = body.toByteArray(Charsets.UTF_8)
    socket.getOutputStream().apply {
      write(
          ("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\n" +
                  "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
              .toByteArray(Charsets.US_ASCII))
      write(bytes)
      flush()
    }
  }

  override fun close() {
    listener.close()
    runCatching { client?.close() }
  }

  private companion object {
    const val PAGE =
        """<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>OsmAnd</title>
<style>body{font:16px system-ui;background:#0a0f13;color:#eee;max-width:540px;margin:24px auto;padding:0 16px}h1{font-size:22px}p{color:#aaa}table{width:100%;border-collapse:collapse}td{padding:12px 0;border-bottom:1px solid #28333c}td:last-child{text-align:right;font-variant-numeric:tabular-nums}#connection{color:#ffbd39}</style>
<h1 id="title">OsmAnd</h1><p id="status"></p><p id="connection"></p><table id="values"></table>
<script>
async function refresh(){
 try {
  const response=await fetch(location.pathname+'/data',{cache:'no-store'});
  if(!response.ok)throw Error();
  const data=await response.json();
  document.title=data.title+' · OsmAnd';
  document.getElementById('title').textContent=data.title;
  document.getElementById('status').textContent=data.status;
  document.getElementById('connection').textContent='';
  const rows=(data.rows||[]).map(pair=>{const row=document.createElement('tr');pair.forEach(value=>{const cell=document.createElement('td');cell.textContent=value;row.append(cell)});return row});
  document.getElementById('values').replaceChildren(...rows);
  window.unavailable=data.unavailable;
 }catch(e){document.getElementById('connection').textContent=window.unavailable||'—'}
 setTimeout(refresh,2000);
}
refresh();
</script>"""
  }
}
