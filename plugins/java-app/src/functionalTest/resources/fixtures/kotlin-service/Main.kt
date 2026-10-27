package test

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val port = System.getenv("APP_PORT")?.toInt() ?: 8080
    if (args.firstOrNull() == "--healthcheck") exitProcess(if (healthy(port)) 0 else 1)
    HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/") {
            it.sendResponseHeaders(200, -1)
            it.close()
        }
        start()
    }
}

private fun healthy(port: Int) =
    runCatching { (URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection).responseCode == 200 }
        .getOrDefault(false)
