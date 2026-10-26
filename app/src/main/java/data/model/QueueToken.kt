package com.example.queueless.data.model

import com.google.firebase.firestore.Exclude

enum class TokenStatus {
    WAITING,
    READY_TO_CALL,
    CALLED,
    SERVED,
    NO_SHOW,
    CANCELLED
}

data class QueueToken(
    val tokenId: String = "",
    val restaurantId: String = "rest_sample_01",
    val restaurantName: String = "QueueLess Counter",
    val customerPhone: String = "",
    val userPhone: String = "",
    val partySize: Int = 1,
    val tokenNumber: Int = 0,
    val status: String = TokenStatus.WAITING.name,
    val timestamp: Long = System.currentTimeMillis(),
    val calledTimestamp: Long? = null,
    val completedTimestamp: Long? = null
) {
    @get:Exclude
    val displayPhone: String
        get() = customerPhone.ifBlank { userPhone }
}