package com.nuvio.app.features.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The protocol and the transport's gatekeeping, against tools that touch
 * nothing: what a client sees for each kind of message, and who is let in.
 */
class McpServerTest {

    private val echo = jsonTool(
        name = "echo",
        description = "Returns its argument.",
        inputSchema = buildJsonObject { put("type", "object") },
        handler = { args -> buildJsonObject { put("said", args["text"] ?: JsonPrimitive("")) } },
    )
    private val refuses = jsonTool(
        name = "refuses",
        description = "Always fails.",
        inputSchema = buildJsonObject { put("type", "object") },
        handler = { throw McpToolException("Pick another stream.") },
    )
    private val dispatcher = McpDispatcher("streamcut", "test", "How to use it.", listOf(echo, refuses))

    private var server: McpHttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop()
    }

    private fun ask(json: String): JsonObject? =
        runBlocking { dispatcher.handle(Json.parseToJsonElement(json)) }?.jsonObject

    // --- protocol ---

    @Test
    fun `initialize echoes a protocol version it knows and offers its newest otherwise`() {
        val known = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")
        assertEquals("2024-11-05", known!!["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)

        val unknown = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
        val result = unknown!!["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
        assertEquals("streamcut", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a notification is never answered`() {
        assertNull(ask("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(ask("""{"jsonrpc":"2.0","method":"no/such/notification"}"""))
    }

    @Test
    fun `each tool says whether it only reads, so a client can let the ones that do run unasked`() {
        val tools = listOf(
            jsonTool("look", "", buildJsonObject { }) { JsonPrimitive(1) },
            jsonTool("make", "", buildJsonObject { }, McpEffect.Changes) { JsonPrimitive(1) },
            jsonTool("remove", "", buildJsonObject { }, McpEffect.Destroys) { JsonPrimitive(1) },
        )
        val listed = runBlocking {
            McpDispatcher("streamcut", "test", "", tools).handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        }!!.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["annotations"]!!.jsonObject }
        assertEquals(listOf(true, false, false), listed.map { it["readOnlyHint"]!!.jsonPrimitive.boolean })
        assertEquals(listOf(false, false, true), listed.map { it["destructiveHint"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun `every tool call is reported with its outcome, and nothing else is`() {
        val calls = ArrayList<String>()
        val watched = McpDispatcher("streamcut", "test", "", listOf(echo, refuses)) { tool, arguments, failed, _ ->
            calls += "$tool $arguments ${if (failed) "failed" else "ok"}"
        }
        runBlocking {
            watched.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
            watched.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}"""))
            watched.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"refuses"}}"""))
        }
        assertEquals(listOf("""echo {"text":"hi"} ok""", "refuses {} failed"), calls)
    }

    @Test
    fun `tools are listed with their schemas`() {
        val tools = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("echo", "refuses"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue("inputSchema" in tools.first().jsonObject)
    }

    @Test
    fun `a tool's result comes back as JSON text`() {
        val reply = ask(
            """{"jsonrpc":"2.0","id":"a","method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""",
        )!!
        assertEquals("a", reply["id"]!!.jsonPrimitive.content)
        val result = reply["result"]!!.jsonObject
        assertFalse(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("""{"said":"hi"}""", result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool that refuses is a result the model can read, not a protocol error`() {
        val reply = ask("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"refuses"}}""")!!
        assertFalse("error" in reply)
        val result = reply["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("Pick another stream.", result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool can answer with pictures alongside text`() {
        val pictures = McpTool("look", "Returns a frame.", buildJsonObject { put("type", "object") }) {
            listOf(McpContent.Text("0:00:01.000"), McpContent.Image(byteArrayOf(1, 2, 3), "image/jpeg"))
        }
        val reply = runBlocking {
            McpDispatcher("streamcut", "test", "", listOf(pictures)).handle(
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"look"}}"""),
            )
        }!!.jsonObject
        val content = reply["result"]!!.jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image"), content.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("AQID", content[1]["data"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", content[1]["mimeType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown tools and methods are protocol errors`() {
        val tool = ask("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope"}}""")!!
        assertEquals(McpDispatcher.InvalidParams, tool["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val method = ask("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}""")!!
        assertEquals(McpDispatcher.MethodNotFound, method["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    // --- transport ---

    private class Response(val status: Int, val body: String)

    private fun runningServer(): McpHttpServer =
        server ?: McpHttpServer(port = 0, token = "secret", dispatcher = dispatcher).also {
            it.start()
            server = it
        }

    private fun post(
        body: String,
        token: String? = "secret",
        method: String = "POST",
    ): Response {
        val connection = URI("http://127.0.0.1:${runningServer().boundPort}/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (method == "POST") {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        return Response(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
    }

    @Test
    fun `a request with the token is answered`() {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(200, response.status)
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", response.body)
    }

    @Test
    fun `a notification is accepted with no body`() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun `no token or the wrong token is refused`() {
        assertEquals(401, post("{}", token = null).status)
        assertEquals(401, post("{}", token = "guess").status)
    }

    /**
     * Status of a ping sent with hand-written headers. HttpURLConnection will
     * not do: it silently drops Origin and Host, the two headers under test.
     */
    private fun rawPingStatus(host: String, origin: String?): Int {
        val port = runningServer().boundPort
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val request = buildString {
            append("POST /mcp HTTP/1.1\r\n")
            append("Host: ").append(host.replace("PORT", port.toString())).append("\r\n")
            if (origin != null) append("Origin: ").append(origin).append("\r\n")
            append("Authorization: Bearer secret\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(body.length).append("\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        return Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(request.toByteArray())
            socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
        }
    }

    @Test
    fun `a web page cannot reach the server even with the token`() {
        assertEquals(200, rawPingStatus(host = "127.0.0.1:PORT", origin = null))
        assertEquals(200, rawPingStatus(host = "localhost:PORT", origin = "http://localhost:3000"))
        assertEquals(403, rawPingStatus(host = "127.0.0.1:PORT", origin = "https://evil.example"))
        // DNS rebinding: the page's own hostname resolved to this machine.
        assertEquals(403, rawPingStatus(host = "evil.example:PORT", origin = null))
    }

    // --- stdio relay ---

    private fun relay(endpoint: String, vararg lines: String): List<JsonObject> {
        val written = ByteArrayOutputStream()
        val offline = McpDispatcher(
            "streamcut",
            "test",
            "",
            listOf(McpTool(echo.name, echo.description, echo.inputSchema) { throw McpToolException("StreamCut is not reachable.") }),
        )
        McpStdioBridge(endpoint, "secret", offline)
            .relay(lines.joinToString("\n").reader().buffered(), PrintStream(written, true))
        return written.toString().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    }

    @Test
    fun `the stdio relay passes each line to the app and answers in order, one line each`() {
        val endpoint = "http://127.0.0.1:${runningServer().boundPort}/mcp"
        val replies = relay(
            endpoint,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            "",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""",
        )
        assertEquals(listOf(1, 2), replies.map { it["id"]!!.jsonPrimitive.int })
        assertEquals(
            """{"said":"hi"}""",
            replies[1]["result"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `with the app closed the relay still connects and a tool call says why it cannot run`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val replies = relay(
            "http://127.0.0.1:$closedPort/mcp",
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo"}}""",
        )
        assertTrue("result" in replies[0])
        assertEquals(1, replies[1]["result"]!!.jsonObject["tools"]!!.jsonArray.size)
        val call = replies[2]["result"]!!.jsonObject
        assertTrue(call["isError"]!!.jsonPrimitive.boolean)
        assertEquals("StreamCut is not reachable.", call["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool call that finds the app closed starts it and goes through`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        var wakes = 0
        val offline = McpDispatcher("streamcut", "test", "", listOf(echo))
        val written = ByteArrayOutputStream()
        McpStdioBridge(
            endpoint = "http://127.0.0.1:$closedPort/mcp",
            token = "secret",
            offline = offline,
            wake = {
                wakes++
                // "Starting the app": the server comes up on the port the relay was told about.
                server = McpHttpServer(port = closedPort, token = "secret", dispatcher = dispatcher).also { it.start() }
                true
            },
        ).relay(
            listOf(
                """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""",
            ).joinToString("\n").reader().buffered(),
            PrintStream(written, true),
        )
        val replies = written.toString().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        // Listing tools is answered offline and wakes nothing; the call is what starts the app.
        assertEquals(1, wakes)
        assertEquals(1, replies[0]["result"]!!.jsonObject["tools"]!!.jsonArray.size)
        assertEquals(
            """{"said":"hi"}""",
            replies[1]["result"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `only POST is offered`() {
        assertEquals(405, post("", method = "GET").status)
    }

    @Test
    fun `malformed JSON is a parse error`() {
        val response = post("{not json")
        assertEquals(400, response.status)
        assertEquals(
            McpDispatcher.ParseError,
            Json.parseToJsonElement(response.body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
        )
    }
}
