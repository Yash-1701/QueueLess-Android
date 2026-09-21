package com.example.queueless.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.queueless.ui.viewmodel.QueueViewModel
import com.example.queueless.util.QRCodeHelper

@Composable
fun LiveTokenScreen(
    viewModel: QueueViewModel,
    restaurantId: String
) {
    val token by viewModel.activeToken.collectAsState()
    val restaurant by viewModel.restaurant.collectAsState()

    LaunchedEffect(restaurantId) {
        viewModel.loadRestaurant(restaurantId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        token?.let { currentToken ->
            Text(currentToken.restaurantName, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Your Token: #${currentToken.tokenNumber}", style = MaterialTheme.typography.displaySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Status: ${currentToken.status}", style = MaterialTheme.typography.bodyLarge)

            restaurant?.let { rest ->
                val ahead = (currentToken.tokenNumber - rest.currentServingToken).coerceAtLeast(0)
                val waitEstimate = (ahead * rest.avgServiceTimeMinutes).toInt()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Now Serving: #${rest.currentServingToken}", style = MaterialTheme.typography.titleMedium)
                Text("People Ahead: $ahead", style = MaterialTheme.typography.bodyMedium)
                Text("Estimated Wait: ~$waitEstimate mins", style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(24.dp))
            val qrBitmap = remember(currentToken.tokenId) {
                QRCodeHelper.generateQRCode(currentToken.tokenId)
            }
            qrBitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = "Token QR Code", modifier = Modifier.size(220.dp))
            }
        } ?: CircularProgressIndicator()
    }
}