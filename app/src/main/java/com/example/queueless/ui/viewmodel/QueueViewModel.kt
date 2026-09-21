package com.example.queueless.ui.viewmodel

import androidx.lifecycle.ViewModel

import androidx.lifecycle.viewModelScope

import com.example.queueless.data.model.QueueToken

import com.example.queueless.data.model.Restaurant

import com.example.queueless.data.repository.QueueRepository

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.launch

class QueueViewModel : ViewModel() {

    private val repository = QueueRepository()

    private val _restaurant = MutableStateFlow<Restaurant?>(null)

    val restaurant: StateFlow<Restaurant?> = _restaurant.asStateFlow()

    private val _activeToken = MutableStateFlow<QueueToken?>(null)

    val activeToken: StateFlow<QueueToken?> = _activeToken.asStateFlow()

    private val _activeTokenId = MutableStateFlow<String?>(null)

    val activeTokenId: StateFlow<String?> = _activeTokenId.asStateFlow()

    fun loadRestaurant(restaurantId: String) {

        viewModelScope.launch {

            repository.observeRestaurant(restaurantId).collect {

                _restaurant.value = it

            }

        }

    }

    fun requestToken(restaurantId: String, phone: String, partySize: Int) {

        viewModelScope.launch {

            val generatedTokenId = repository.joinQueue(restaurantId, phone, partySize)

            _activeTokenId.value = generatedTokenId

            repository.observeUserToken(generatedTokenId).collect {

                _activeToken.value = it

            }

        }

    }

    fun adminCallNext(restaurantId: String, currentServing: Int) {

        viewModelScope.launch {

            repository.callNextCustomer(restaurantId, currentServing, 10.0)

        }

    }

}

