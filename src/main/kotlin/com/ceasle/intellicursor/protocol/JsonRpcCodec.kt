package com.ceasle.intellicursor.protocol

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object JsonRpcCodec {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    fun encode(obj: Any): String = mapper.writeValueAsString(obj)

    fun readTree(json: String): JsonNode = mapper.readTree(json)

    fun decodeNotification(json: String): JsonRpcNotification {
        val root = mapper.readTree(json)
        val method = root.get("method").asText()
        val paramsNode = root.get("params")
        val params = when (method) {
            "session/update" -> mapper.treeToValue(paramsNode, SessionUpdateNotification::class.java)
            else -> paramsNode
        }
        return JsonRpcNotification(method = method, params = params)
    }

    inline fun <reified T> fromNode(node: JsonNode): T = mapper.treeToValue(node, T::class.java)
}
