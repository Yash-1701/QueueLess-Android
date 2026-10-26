package com.example.queueless.data.model

import com.google.firebase.firestore.PropertyName

data class Restaurant(
    val id: String = "rest_sample_01",
    val name: String = "QueueLess Counter",
    val cuisine: String = "Fast Casual",
    val currentServingToken: Int = 0,
    val totalTokensIssued: Int = 0,
    val lastIssuedToken: Int = 0,
    val currentEmaWaitTime: Double = 5.0,
    val recentServiceTimes: List<Double> = emptyList(),
    @get:PropertyName("isQueuePaused")
    @set:PropertyName("isQueuePaused")
    var isQueuePaused: Boolean = false,
    val latitude: Double = 19.141806,
    val longitude: Double = 72.833361
)