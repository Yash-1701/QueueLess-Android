package com.example.queueless.data.repository

import com.example.queueless.data.model.QueueToken

import com.example.queueless.data.model.Restaurant

import com.example.queueless.data.model.TokenStatus

import com.google.firebase.firestore.FirebaseFirestore

import kotlinx.coroutines.channels.awaitClose

import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.callbackFlow

import kotlinx.coroutines.tasks.await

class QueueRepository {

    private val firestore = FirebaseFirestore.getInstance()

    fun observeRestaurant(restaurantId: String): Flow<Restaurant?> = callbackFlow {

        val listener = firestore.collection("restaurants").document(restaurantId)

            .addSnapshotListener { snapshot, error ->

                if (error != null) return@addSnapshotListener

                val restaurant = snapshot?.toObject(Restaurant::class.java)

                trySend(restaurant)

            }

        awaitClose { listener.remove() }

    }

    fun observeUserToken(tokenId: String): Flow<QueueToken?> = callbackFlow {

        val listener = firestore.collection("tokens").document(tokenId)

            .addSnapshotListener { snapshot, error ->

                if (error != null) return@addSnapshotListener

                val token = snapshot?.toObject(QueueToken::class.java)

                trySend(token)

            }

        awaitClose { listener.remove() }

    }

    suspend fun joinQueue(restaurantId: String, userPhone: String, partySize: Int): String {

        val restRef = firestore.collection("restaurants").document(restaurantId)

        val tokenRef = firestore.collection("tokens").document()

        firestore.runTransaction { transaction ->

            val snapshot = transaction.get(restRef)

            val currentLastToken = snapshot.getLong("lastIssuedToken")?.toInt() ?: 0

            val newTokenNumber = currentLastToken + 1

            val restName = snapshot.getString("name") ?: "Restaurant"

            transaction.update(restRef, "lastIssuedToken", newTokenNumber)

            val tokenData = QueueToken(

                tokenId = tokenRef.id,

                restaurantId = restaurantId,

                restaurantName = restName,

                userPhone = userPhone,

                partySize = partySize,

                tokenNumber = newTokenNumber,

                status = TokenStatus.WAITING.name,

                timestamp = System.currentTimeMillis()

            )

            transaction.set(tokenRef, tokenData)

        }.await()

        return tokenRef.id

    }

    suspend fun callNextCustomer(restaurantId: String, currentServing: Int, elapsedMinutes: Double) {

        val restRef = firestore.collection("restaurants").document(restaurantId)

        firestore.runTransaction { transaction ->

            val snapshot = transaction.get(restRef)

            val currentEma = snapshot.getDouble("avgServiceTimeMinutes") ?: 12.0

            val updatedEma = (0.3 * elapsedMinutes) + (0.7 * currentEma)

            transaction.update(

                restRef,

                mapOf(

                    "currentServingToken" to currentServing + 1,

                    "avgServiceTimeMinutes" to updatedEma

                )

            )

        }.await()

    }

}
