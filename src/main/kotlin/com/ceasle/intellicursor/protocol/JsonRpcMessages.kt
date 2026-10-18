package com.ceasle.intellicursor.protocol

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: Any? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long,
    val result: Any? = null,
    val error: JsonRpcError? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Any? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)
