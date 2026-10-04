package com.nuvio.app.features.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/** A failure the assistant can act on: its message is returned as the tool's result. */
internal class McpToolException(message: String) : Exception(message)

/** One piece of a tool's answer. Most tools answer with a single [Text] holding JSON. */
internal sealed interface McpContent {
    class Text(val text: String) : McpContent

    /** A picture the model looks at directly, rather than a path it would have to open. */
    class Image(val bytes: ByteArray, val mimeType: String) : McpContent
}

/**
 * What a tool does to the world, which is what a client decides its
 * permission prompt on. A tool that only reads can be approved once and left
 * to run; without this every search in an unattended job stops to ask.
 */
internal enum class McpEffect {
    /** Looks, and changes nothing. */
    Reads,

    /** Adds or alters something -- a new clip, a new name, a window -- and loses nothing. */
    Changes,

    /** Removes or replaces something the user had. */
    Destroys,
}

internal class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val effect: McpEffect = McpEffect.Reads,
    val handler: suspend (arguments: JsonObject) -> List<McpContent>,
)

/** A tool whose whole answer is one JSON value, which is all but the ones that return pictures. */
internal fun jsonTool(
    name: String,
    description: String,
    inputSchema: JsonObject,
    effect: McpEffect = McpEffect.Reads,
    handler: suspend (arguments: JsonObject) -> JsonElement,
): McpTool = McpTool(name, description, inputSchema, effect) { arguments ->
    listOf(McpContent.Text(handler(arguments).toString()))
}

/**
 * The Model Context Protocol, as far as a tools-only server needs it: answers
 * `initialize`, `ping`, `tools/list` and `tools/call` over JSON-RPC 2.0.
 *
 * Written by hand rather than pulled in as the MCP SDK because that brings a
 * Ktor server and its engine into a desktop bundle that today ships only the
 * Ktor client, for four methods' worth of protocol. Transport lives in
 * [McpHttpServer]; this class never sees a socket, which is what makes it
 * testable.
 */
internal class McpDispatcher(
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String,
    tools: List<McpTool>,
    /** Told about every tool call once it has ended: name, arguments, whether it failed, how long it took. */
    private val onToolCall: (tool: String, arguments: JsonObject, failed: Boolean, tookMs: Long) -> Unit = { _, _, _, _ -> },
) {
    private val tools = tools.associateBy { it.name }

    /** The reply to [message], or null when it was a notification and nothing is owed. */
    suspend fun handle(message: JsonElement): JsonElement? = when (message) {
        is JsonArray -> message.mapNotNull { handle(it) }.takeIf { it.isNotEmpty() }?.let(::JsonArray)
        is JsonObject -> handleOne(message)
        else -> errorReply(JsonNull, InvalidRequest, "Expected a JSON-RPC object")
    }

    private suspend fun handleOne(message: JsonObject): JsonElement? {
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        // No id is a notification: the spec forbids answering it, even with an error.
        val id = message["id"]?.takeIf { it !is JsonNull }
        if (method == null) {
            // A response to a request this server never makes.
            return if (id == null || "result" in message || "error" in message) null
            else errorReply(id, InvalidRequest, "Missing method")
        }
        if (id == null) return null

        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> reply(id, initializeResult(params))
            "ping" -> reply(id, JsonObject(emptyMap()))
            "tools/list" -> reply(id, toolsListResult())
            "tools/call" -> callTool(id, params)
            else -> errorReply(id, MethodNotFound, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in SupportedVersions } ?: SupportedVersions.first())
            put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", serverName)
                    put("version", serverVersion)
                },
            )
            put("instructions", instructions)
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                tools.values.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                            put(
                                "annotations",
                                buildJsonObject {
                                    put("readOnlyHint", tool.effect == McpEffect.Reads)
                                    put("destructiveHint", tool.effect == McpEffect.Destroys)
                                    // Every tool reaches the user's addons or disk, never the open web.
                                    put("openWorldHint", false)
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
        val tool = tools[name] ?: return errorReply(id, InvalidParams, "Unknown tool: $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        // A tool that fails is still a successful call as far as JSON-RPC goes:
        // the failure goes back as the result, where the model reads it and can
        // correct course, instead of as a protocol error the client swallows.
        val startedAt = System.nanoTime()
        val (content, isError) = try {
            tool.handler(arguments) to false
        } catch (error: CancellationException) {
            throw error
        } catch (error: McpToolException) {
            listOf(McpContent.Text(error.message.orEmpty())) to true
        } catch (error: Throwable) {
            listOf(McpContent.Text("${tool.name} failed: ${error.message ?: error::class.simpleName}")) to true
        }
        onToolCall(tool.name, arguments, isError, (System.nanoTime() - startedAt) / 1_000_000)
        return reply(
            id,
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        content.forEach { item ->
                            add(
                                buildJsonObject {
                                    when (item) {
                                        is McpContent.Text -> {
                                            put("type", "text")
                                            put("text", item.text)
                                        }
                                        is McpContent.Image -> {
                                            put("type", "image")
                                            put("data", Base64.getEncoder().encodeToString(item.bytes))
                                            put("mimeType", item.mimeType)
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
                put("isError", isError)
            },
        )
    }

    companion object {
        const val ParseError = -32700
        const val InvalidRequest = -32600
        const val MethodNotFound = -32601
        const val InvalidParams = -32602

        /** Newest first; the first entry is what a client asking for an unknown revision is offered. */
        private val SupportedVersions = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        private fun reply(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun errorReply(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }
    }
}
