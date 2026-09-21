package com.example.queueless.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.queueless.ui.viewmodel.QueueViewModel

@Composable
fun QueueScreen(
    viewModel: QueueViewModel,
    restaurantId: String,
    onTokenGenerated: () -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var partySize by remember { mutableStateOf("2") }
    val activeTokenId by viewModel.activeTokenId.collectAsState()

    LaunchedEffect(activeTokenId) {
        if (activeTokenId != null) {
            onTokenGenerated()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Join Digital Queue", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone Number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = partySize,
            onValueChange = { partySize = it },
            label = { Text("Party Size") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val size = partySize.toIntOrNull() ?: 1
                if (phone.isNotBlank()) {
                    viewModel.requestToken(restaurantId, phone, size)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Token")
        }
    }
}