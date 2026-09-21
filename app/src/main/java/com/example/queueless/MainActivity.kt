package com.example.queueless

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.example.queueless.ui.screens.LiveTokenScreen
import com.example.queueless.ui.screens.QueueScreen
import com.example.queueless.ui.theme.QueueLessTheme
import com.example.queueless.ui.viewmodel.QueueViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: QueueViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QueueLessTheme {
                var currentScreen by remember { mutableStateOf("join") }
                val restaurantId = "rest_sample_01"

                if (currentScreen == "join") {
                    QueueScreen(
                        viewModel = viewModel,
                        restaurantId = restaurantId,
                        onTokenGenerated = { currentScreen = "live" }
                    )
                } else {
                    LiveTokenScreen(
                        viewModel = viewModel,
                        restaurantId = restaurantId
                    )
                }
            }
        }
    }
}