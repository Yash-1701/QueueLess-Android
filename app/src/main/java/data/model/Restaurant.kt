package com.example.queueless.data.model

data class Restaurant(
    val id: String = "",
    val name: String = "",
    val cuisine: String = "",
    val currentServingToken: Int = 0,
    val lastIssuedToken: Int = 0,
    val avgServiceTimeMinutes: Double = 12.0
)