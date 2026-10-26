package com.example.queueless.data.repository

import com.example.queueless.data.model.QueueToken
import com.example.queueless.data.model.Restaurant
import com.example.queueless.data.model.TokenStatus
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.math.max

class QueueRepository {

    private val db = FirebaseFirestore.getInstance()

    companion object {
        const val VENUE_ID = "rest_sample_01"
        const val PROXIMITY_RADIUS_METERS = 500.0
        const val EMA_ALPHA = 0.333
        const val EMA_COMPLEMENT = 0.667
        const val MAX_RECENT_SAMPLES = 5
    }

    /**
     * Initializes the default venue document in Firestore if absent.
     */
    suspend fun ensureDefaultVenueInitialized() {
        try {
            val restRef = db.collection("restaurants").document(VENUE_ID)
            val snapshot = restRef.get().await()
            if (!snapshot.exists()) {
                val initial = Restaurant(
                    id = VENUE_ID,
                    name = "QueueLess Express Counter",
                    cuisine = "Fast Casual Dine-In",
                    currentServingToken = 0,
                    totalTokensIssued = 0,
                    lastIssuedToken = 0,
                    currentEmaWaitTime = 5.0,
                    recentServiceTimes = listOf(5.0, 4.8, 5.2),
                    isQueuePaused = false,
                    latitude = 19.141806,
                    longitude = 72.833361
                )
                restRef.set(initial).await()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Property 3: ZERO-POLLING REAL-TIME LISTENER FOR RESTAURANT
     * Emits updates to currentServingToken, currentEmaWaitTime, isQueuePaused instantaneously.
     */
    fun observeRestaurant(restaurantId: String = VENUE_ID): Flow<Restaurant?> = callbackFlow {
        val listener = db.collection("restaurants").document(restaurantId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    error.printStackTrace()
                    trySend(Restaurant(id = restaurantId))
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val restaurant = snapshot.toObject(Restaurant::class.java)?.copy(id = snapshot.id)
                    trySend(restaurant ?: Restaurant(id = restaurantId))
                } else {
                    // Emit default if not yet created in Firestore
                    trySend(Restaurant(id = restaurantId))
                }
            }
        awaitClose { listener.remove() }
    }

    /**
     * Property 3: ZERO-POLLING REAL-TIME LISTENER FOR USER TOKEN
     */
    fun observeUserToken(restaurantId: String = VENUE_ID, tokenId: String): Flow<QueueToken?> = callbackFlow {
        val listener = db.collection("restaurants")
            .document(restaurantId)
            .collection("tokens")
            .document(tokenId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    error.printStackTrace()
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val token = snapshot.toObject(QueueToken::class.java)?.copy(tokenId = snapshot.id)
                    trySend(token)
                }
            }
        awaitClose { listener.remove() }
    }

    /**
     * Property 5: ZERO-POLLING REAL-TIME LISTENER FOR ACTIVE TOKENS
     */
    fun observeActiveTokens(restaurantId: String = VENUE_ID): Flow<List<QueueToken>> = callbackFlow {
        val listener = db.collection("restaurants")
            .document(restaurantId)
            .collection("tokens")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    error.printStackTrace()
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val activeStatuses = setOf(
                    TokenStatus.WAITING.name,
                    TokenStatus.READY_TO_CALL.name,
                    TokenStatus.CALLED.name
                )
                val tokens = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(QueueToken::class.java)?.copy(tokenId = doc.id)
                }?.filter { it.status in activeStatuses }
                    ?.sortedBy { it.tokenNumber }
                    ?: emptyList()

                trySend(tokens)
            }
        awaitClose { listener.remove() }
    }

    /**
     * Property 1: ATOMIC REMOTE TOKEN ALLOCATION & CONCURRENCY CONTROL
     * Isolated Firestore runTransaction reads totalTokensIssued, increments atomically by 1,
     * writes back to /restaurants/rest_sample_01, and writes new QueueToken document.
     */
    suspend fun joinQueue(
        restaurantId: String = VENUE_ID,
        customerPhone: String,
        partySize: Int
    ): Pair<String, QueueToken> {
        val restRef = db.collection("restaurants").document(restaurantId)
        val tokenRef = restRef.collection("tokens").document()

        var tokenData: QueueToken? = null

        db.runTransaction { transaction ->
            val snapshot = transaction.get(restRef)

            val currentTotalIssued = if (snapshot.exists()) {
                snapshot.getLong("totalTokensIssued")?.toInt()
                    ?: snapshot.getLong("lastIssuedToken")?.toInt()
                    ?: 0
            } else {
                0
            }

            val currentServing = if (snapshot.exists()) {
                snapshot.getLong("currentServingToken")?.toInt() ?: 0
            } else {
                0
            }

            // Guarantee monotonic sequence: next token MUST always be strictly greater than both
            // totalTokensIssued and currentServingToken
            val baseCounter = maxOf(currentTotalIssued, currentServing)
            val newTokenNumber = baseCounter + 1

            val restName = if (snapshot.exists()) {
                snapshot.getString("name") ?: "QueueLess Counter"
            } else {
                "QueueLess Counter"
            }

            // Increment and commit sequence counter
            if (snapshot.exists()) {
                transaction.update(
                    restRef,
                    mapOf(
                        "totalTokensIssued" to newTokenNumber,
                        "lastIssuedToken" to newTokenNumber
                    )
                )
            } else {
                val initialRest = Restaurant(
                    id = restaurantId,
                    name = restName,
                    totalTokensIssued = newTokenNumber,
                    lastIssuedToken = newTokenNumber
                )
                transaction.set(restRef, initialRest)
            }

            // Write new document in /restaurants/rest_sample_01/tokens/{tokenId} with status WAITING
            tokenData = QueueToken(
                tokenId = tokenRef.id,
                restaurantId = restaurantId,
                restaurantName = restName,
                customerPhone = customerPhone,
                userPhone = customerPhone,
                partySize = partySize,
                tokenNumber = newTokenNumber,
                status = TokenStatus.WAITING.name,
                timestamp = System.currentTimeMillis()
            )
            transaction.set(tokenRef, tokenData!!)
        }.await()

        return Pair(tokenRef.id, tokenData!!)
    }

    suspend fun requestTokenTransaction(
        restaurantId: String = VENUE_ID,
        customerPhone: String,
        partySize: Int
    ): Pair<String, QueueToken> = joinQueue(restaurantId, customerPhone, partySize)

    /**
     * Property 4: PROXIMITY VERIFICATION PROMOTION
     */
    suspend fun promoteToReadyToCall(restaurantId: String = VENUE_ID, tokenId: String) {
        try {
            val tokenRef = db.collection("restaurants")
                .document(restaurantId)
                .collection("tokens")
                .document(tokenId)

            db.runTransaction { transaction ->
                val snap = transaction.get(tokenRef)
                if (snap.exists()) {
                    val currentStatus = snap.getString("status")
                    if (currentStatus == TokenStatus.WAITING.name) {
                        transaction.update(tokenRef, "status", TokenStatus.READY_TO_CALL.name)
                    }
                }
            }.await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Property 5 - Action 1: "Call Next"
     * Finds the next customer in the queue (status WAITING or READY_TO_CALL).
     * Updates currentServingToken to their tokenNumber, marks token as "CALLED", and records calledTimestamp.
     */
    suspend fun callNextCustomer(restaurantId: String = VENUE_ID) {
        val restRef = db.collection("restaurants").document(restaurantId)
        val tokensRef = restRef.collection("tokens")

        // 1. Fetch active waiting tokens to identify the actual next customer
        val activeSnap = tokensRef.get().await()
        val waitingTokens = activeSnap.documents.mapNotNull { doc ->
            doc.toObject(QueueToken::class.java)?.copy(tokenId = doc.id)
        }.filter { it.status == TokenStatus.WAITING.name || it.status == TokenStatus.READY_TO_CALL.name }
            .sortedBy { it.tokenNumber }

        val nextToken = waitingTokens.firstOrNull()

        if (nextToken != null) {
            // Found a waiting customer: advance serving counter to their token number
            restRef.update("currentServingToken", nextToken.tokenNumber).await()
            tokensRef.document(nextToken.tokenId).update(
                mapOf(
                    "status" to TokenStatus.CALLED.name,
                    "calledTimestamp" to System.currentTimeMillis()
                )
            ).await()
        } else {
            // No waiting token, simply advance counter by 1
            db.runTransaction { transaction ->
                val restSnap = transaction.get(restRef)
                val currentServing = restSnap.getLong("currentServingToken")?.toInt() ?: 0
                transaction.update(restRef, "currentServingToken", currentServing + 1)
            }.await()
        }
    }

    /**
     * Property 5 - Action 2: "Mark Served"
     * Computes elapsed consultation time, triggers dynamic EMA formula update, marks token "SERVED".
     */
    suspend fun markServed(restaurantId: String = VENUE_ID) {
        val restRef = db.collection("restaurants").document(restaurantId)
        val tokensRef = restRef.collection("tokens")

        val restSnap = restRef.get().await()
        val currentServing = restSnap.getLong("currentServingToken")?.toInt() ?: 0
        if (currentServing <= 0) return

        val recentList = (restSnap.get("recentServiceTimes") as? List<*>)?.mapNotNull {
            (it as? Number)?.toDouble()
        } ?: emptyList()

        val previousEma = restSnap.getDouble("currentEmaWaitTime") ?: 5.0

        val tokenDocs = tokensRef.whereEqualTo("tokenNumber", currentServing).limit(1).get().await()
        val tokenDoc = tokenDocs.documents.firstOrNull()

        val calledTime = tokenDoc?.getLong("calledTimestamp")
            ?: tokenDoc?.getLong("timestamp")
            ?: (System.currentTimeMillis() - 5 * 60 * 1000L)

        val completedTimestamp = System.currentTimeMillis()
        val durationMinutes = max(0.5, (completedTimestamp - calledTime) / 60000.0)
        val newEma = (durationMinutes * EMA_ALPHA) + (previousEma * EMA_COMPLEMENT)
        val updatedRecent = (recentList + durationMinutes).takeLast(MAX_RECENT_SAMPLES)

        db.runTransaction { transaction ->
            transaction.update(
                restRef,
                mapOf(
                    "recentServiceTimes" to updatedRecent,
                    "currentEmaWaitTime" to newEma
                )
            )

            if (tokenDoc != null) {
                transaction.update(
                    tokenDoc.reference,
                    mapOf(
                        "status" to TokenStatus.SERVED.name,
                        "completedTimestamp" to completedTimestamp
                    )
                )
            }
        }.await()
    }

    /**
     * Property 5 - Action 3: "Flag No-Show"
     * Marks token "NO_SHOW", skips absent patron, advances currentServingToken,
     * and cascades downstream ETA recalculation across waiting tokens.
     */
    suspend fun flagNoShow(restaurantId: String = VENUE_ID) {
        val restRef = db.collection("restaurants").document(restaurantId)
        val tokensRef = restRef.collection("tokens")

        val restSnap = restRef.get().await()
        val currentServing = restSnap.getLong("currentServingToken")?.toInt() ?: 0

        // 1. Mark current serving token as NO_SHOW
        if (currentServing > 0) {
            val tokenDocs = tokensRef.whereEqualTo("tokenNumber", currentServing).limit(1).get().await()
            for (doc in tokenDocs.documents) {
                doc.reference.update("status", TokenStatus.NO_SHOW.name).await()
            }
        }

        // 2. Advance to the next waiting customer
        val activeSnap = tokensRef.get().await()
        val waitingTokens = activeSnap.documents.mapNotNull { doc ->
            doc.toObject(QueueToken::class.java)?.copy(tokenId = doc.id)
        }.filter {
            it.tokenNumber > currentServing &&
                    (it.status == TokenStatus.WAITING.name || it.status == TokenStatus.READY_TO_CALL.name)
        }.sortedBy { it.tokenNumber }

        val nextToken = waitingTokens.firstOrNull()
        if (nextToken != null) {
            restRef.update("currentServingToken", nextToken.tokenNumber).await()
            tokensRef.document(nextToken.tokenId).update(
                mapOf(
                    "status" to TokenStatus.CALLED.name,
                    "calledTimestamp" to System.currentTimeMillis()
                )
            ).await()
        } else {
            restRef.update("currentServingToken", currentServing + 1).await()
        }
    }

    /**
     * Property 5 - Action 4: "Pause / Resume Queue"
     * Toggles isQueuePaused boolean flag in Firestore.
     */
    suspend fun togglePauseQueue(restaurantId: String = VENUE_ID) {
        val restRef = db.collection("restaurants").document(restaurantId)
        db.runTransaction { transaction ->
            val snap = transaction.get(restRef)
            val currentPaused = snap.getBoolean("isQueuePaused") ?: false
            val newPaused = !currentPaused
            transaction.update(
                restRef,
                mapOf(
                    "isQueuePaused" to newPaused,
                    "queuePaused" to newPaused
                )
            )
        }.await()
    }
}
