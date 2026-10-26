package com.example.queueless

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.queueless.ui.screens.AdminCounterScreen
import com.example.queueless.ui.screens.LiveTokenScreen
import com.example.queueless.ui.screens.QueueScreen
import com.example.queueless.ui.theme.QueueLessTheme
import com.example.queueless.ui.viewmodel.QueueViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: QueueViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QueueLessTheme {
                var isVendorMode by remember { mutableStateOf(false) }
                val activeTokenId by viewModel.activeTokenId.collectAsState()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = if (isVendorMode) "QueueLess • Vendor Admin" else "QueueLess",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = if (isVendorMode) "Live Counter Management" else "Fast-Casual Queue",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            actions = {
                                // Faculty Evaluation & Testing Toggle Button
                                FilledTonalButton(
                                    onClick = { isVendorMode = !isVendorMode },
                                    modifier = Modifier.padding(end = 8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isVendorMode) Icons.Default.Person else Icons.Default.AdminPanelSettings,
                                        contentDescription = "Switch View",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isVendorMode) "Customer View" else "Vendor View",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (isVendorMode) {
                            AdminCounterScreen(viewModel = viewModel)
                        } else {
                            if (activeTokenId == null) {
                                QueueScreen(
                                    viewModel = viewModel,
                                    onTokenGenerated = { /* Handled reactively via activeTokenId StateFlow */ }
                                )
                            } else {
                                LiveTokenScreen(
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}