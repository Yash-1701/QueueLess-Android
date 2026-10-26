package com.example.queueless.ui.viewmodel

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.queueless.data.model.QueueToken
import com.example.queueless.data.model.Restaurant
import com.example.queueless.data.repository.QueueRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class QueueViewModel : ViewModel() {

    private val repository = QueueRepository()
    val venueId = QueueRepository.VENUE_ID

    // Non-null baseline ensures UI renders immediately without waiting
    private val _restaurant = MutableStateFlow<Restaurant?>(Restaurant(id = venueId))
    val restaurant: StateFlow<Restaurant?> = _restaurant.asStateFlow()

    private val _activeToken = MutableStateFlow<QueueToken?>(null)
    val activeToken: StateFlow<QueueToken?> = _activeToken.asStateFlow()

    private val _activeTokenId = MutableStateFlow<String?>(null)
    val activeTokenId: StateFlow<String?> = _activeTokenId.asStateFlow()

    private val _activeTokens = MutableStateFlow<List<QueueToken>>(emptyList())
    val activeTokens: StateFlow<List<QueueToken>> = _activeTokens.asStateFlow()

    private val _proximityStatus = MutableStateFlow<String>("Awaiting location check...")
    val proximityStatus: StateFlow<String> = _proximityStatus.asStateFlow()

    private var tokenObservationJob: Job? = null

    init {
        // 1. Start zero-polling snapshot listener for venue document immediately
        viewModelScope.launch {
            repository.observeRestaurant(venueId).collect {
                if (it != null) {
                    _restaurant.value = it
                }
            }
        }

        // 2. Start zero-polling snapshot listener for active tokens in queue immediately
        viewModelScope.launch {
            repository.observeActiveTokens(venueId).collect {
                _activeTokens.value = it
            }
        }

        // 3. Ensure default venue document exists in Firestore (non-blocking background task)
        viewModelScope.launch {
            repository.ensureDefaultVenueInitialized()
        }
    }

    /**
     * Property 1: Request atomic token allocation for rest_sample_01
     */
    fun requestToken(customerPhone: String, partySize: Int, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val (generatedTokenId, tokenData) = repository.joinQueue(
                    restaurantId = venueId,
                    customerPhone = customerPhone,
                    partySize = partySize
                )
                // Instantly update active token state so screen transitions immediately
                _activeTokenId.value = generatedTokenId
                _activeToken.value = tokenData
                observeToken(generatedTokenId)
                onSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun observeToken(tokenId: String) {
        tokenObservationJob?.cancel()
        tokenObservationJob = viewModelScope.launch {
            repository.observeUserToken(venueId, tokenId).collect { liveToken ->
                if (liveToken != null) {
                    _activeToken.value = liveToken
                }
            }
        }
    }

    /**
     * Property 4: Location distance check against venue coordinates
     */
    fun checkProximity(userLat: Double, userLng: Double, restLat: Double, restLng: Double) {
        val currentToken = _activeToken.value ?: return

        val results = FloatArray(1)
        Location.distanceBetween(userLat, userLng, restLat, restLng, results)
        val distanceMeters = results[0].toDouble()

        if (distanceMeters <= QueueRepository.PROXIMITY_RADIUS_METERS) {
            _proximityStatus.value = "Within range (${distanceMeters.toInt()}m): Ready to be called"
            viewModelScope.launch {
                try {
                    repository.promoteToReadyToCall(venueId, currentToken.tokenId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            _proximityStatus.value = "Distance: ${distanceMeters.toInt()}m (Requires <= 500m to enter Ready status)"
        }
    }

    /**
     * Manual fallback for emulators or denied location permissions
     */
    fun manualPromoteProximity() {
        val currentToken = _activeToken.value ?: return
        _proximityStatus.value = "Proximity verified manually (Demonstration Mode)"
        viewModelScope.launch {
            try {
                repository.promoteToReadyToCall(venueId, currentToken.tokenId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Property 5: Admin Counter Actions
     */
    fun adminCallNext() {
        viewModelScope.launch {
            try {
                repository.callNextCustomer(venueId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun adminMarkServed() {
        viewModelScope.launch {
            try {
                repository.markServed(venueId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun adminFlagNoShow() {
        viewModelScope.launch {
            try {
                repository.flagNoShow(venueId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun adminTogglePause() {
        viewModelScope.launch {
            try {
                repository.togglePauseQueue(venueId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun resetCustomerQueue() {
        tokenObservationJob?.cancel()
        _activeTokenId.value = null
        _activeToken.value = null
        _proximityStatus.value = "Awaiting location check..."
    }
}
