package io.github.fiftieshousewife.appcore

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * An HTTP repository on the loopback interface that accepts every upload with `201 Created` and
 * records each request as `<method> <path> <Authorization header>`. Plain sockets rather than the
 * JDK's HTTP server keep the test fixtures on portable APIs.
 */
class RecordingRepository : AutoCloseable {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "recording-repository") {
            while (!socket.isClosed) {
                runCatching { socket.accept().use(::answer) }
            }
        }
    }

    private fun answer(client: Socket) {
        val input = client.getInputStream().buffered()
        val lines = generateSequence { readLine(input) }.takeWhile(String::isNotEmpty).toList()
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        input.readNBytes(headers["content-length"]?.toInt() ?: 0)
        requests += "${lines.first().split(' ').take(2).joinToString(" ")} ${headers["authorization"]}"
        client.getOutputStream().write(RESPONSE.toByteArray())
    }

    /** One CRLF-terminated header line, read byte by byte so the body stays in the stream. */
    private fun readLine(input: java.io.InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        return line.toString()
    }

    override fun close() = socket.close()

    private companion object {
        const val BACKLOG = 16
        const val RESPONSE = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
