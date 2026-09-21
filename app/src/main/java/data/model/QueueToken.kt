package com.example.queueless.data.model

enum class TokenStatus {
    WAITING, CALLED, SERVED, NO_SHOW, CANCELLED
}

data class QueueToken(
    val tokenId: String = "",
    val restaurantId: String = "",
    val restaurantName: String = "",
    val userPhone: String = "",
    val partySize: Int = 2,
    val tokenNumber: Int = 0,
    val status: String = TokenStatus.WAITING.name,
    val timestamp: Long = System.currentTimeMillis()
)