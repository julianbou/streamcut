package com.nuvio.app.features.mcp

import co.touchlab.kermit.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP's Streamable HTTP transport on the JDK's own server: one endpoint that
 * takes a JSON-RPC message per POST and answers with plain JSON. The spec's
 * optional server-to-client stream is not offered -- this server never has
 * anything to say unprompted -- so GET is answered 405, as the spec allows.
 *
 * Three things stand between a web page and this port, because a page can make
 * a browser send requests to localhost: the socket is bound to loopback only,
 * the Host and Origin headers must name this machine (which defeats DNS
 * rebinding), and every request needs the bearer token.
 */
internal class McpHttpServer(
    private val port: Int,
    private val token: String,
    private val dispatcher: McpDispatcher,
) {
    private val log = Logger.withTag("McpServer")
    private var server: HttpServer? = null

    /** The port actually bound, which differs from the one asked for when that was 0. */
    val boundPort: Int get() = server?.address?.port ?: port

    /** Throws when the port cannot be bound, e.g. because another instance holds it. */
    fun start() {
        if (server != null) return
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val created = HttpServer.create(InetSocketAddress(loopback, port), 0)
        created.createContext(Path) { exchange ->
            exchange.use {
                // Without this a failure closes the socket with nothing sent,
                // and the client is left to guess from a dropped connection.
                runCatching { respond(it) }.onFailure { failure ->
                    log.e(failure) { "MCP request failed" }
                    runCatching { it.send(500) }
                }
            }
        }
        val threadNumber = AtomicInteger()
        created.executor = Executors.newCachedThreadPool { task ->
            Thread(task, "mcp-request-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
        }
        // The server's dispatcher thread inherits daemon status from whichever
        // thread starts it. Started from a daemon thread, it cannot keep the JVM
        // alive after the window closes.
        Thread({ created.start() }, "mcp-start").apply {
            isDaemon = true
            start()
            join()
        }
        server = created
        log.i { "MCP server listening on 127.0.0.1:${created.address.port}" }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun respond(exchange: HttpExchange) {
        if (exchange.requestURI.path != Path) return exchange.send(404)
        if (!exchange.isLocalRequest()) return exchange.send(403)
        if (!exchange.hasValidToken()) {
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer")
            return exchange.send(401)
        }
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.add("Allow", "POST")
            return exchange.send(405)
        }

        val body = exchange.requestBody.readNBytes(MaxBodyBytes + 1)
        if (body.size > MaxBodyBytes) return exchange.send(413)
        val message = runCatching { Json.parseToJsonElement(body.decodeToString()) }.getOrElse {
            return exchange.sendJson(
                400,
                McpDispatcher.errorReply(JsonNull, McpDispatcher.ParseError, "Parse error").toString(),
            )
        }

        val reply = runBlocking(Dispatchers.Default) {
            withTimeoutOrNull(RequestTimeoutMs) { Answer(dispatcher.handle(message)?.toString()) }
        } ?: return exchange.send(504)
        if (reply.json == null) exchange.send(202) else exchange.sendJson(200, reply.json)
    }

    /** Wraps the reply so a notification (null JSON) is told apart from a timeout (null wrapper). */
    private class Answer(val json: String?)

    private fun HttpExchange.isLocalRequest(): Boolean {
        val host = requestHeaders.getFirst("Host")?.let(::hostOf) ?: return false
        if (host !in LocalHosts) return false
        // Browsers always send Origin on a cross-site POST; MCP clients do not send one at all.
        val origin = requestHeaders.getFirst("Origin") ?: return true
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        return originHost.lowercase() in LocalHosts
    }

    private fun HttpExchange.hasValidToken(): Boolean {
        val presented = requestHeaders.getFirst("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)
            ?.trim()
            ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
    }

    private fun HttpExchange.send(status: Int) {
        sendResponseHeaders(status, -1)
    }

    private fun HttpExchange.sendJson(status: Int, json: String) {
        val bytes = json.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    companion object {
        const val Path = "/mcp"
        private const val MaxBodyBytes = 1 shl 20

        /** Longer than any tool waits on its own, so a tool's timeout message wins over a bare 504. */
        private const val RequestTimeoutMs = 120_000L

        private val LocalHosts = setOf("127.0.0.1", "localhost")

        /** `127.0.0.1:47800` -> `127.0.0.1`. */
        private fun hostOf(header: String): String = header.trim().lowercase().substringBefore(':')
    }
}
