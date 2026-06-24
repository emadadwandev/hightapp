package com.smartmirror.heightmeasure

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.smartmirror.heightmeasure.measurement.MeasurementViewModel
import com.smartmirror.heightmeasure.ui.MeasurementScreen
import com.smartmirror.heightmeasure.ui.SettingsScreen
import com.smartmirror.heightmeasure.ui.theme.HeightMeasureTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HeightMeasureTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    // Single ViewModel shared across both screens so settings are immediately reflected
    val viewModel: MeasurementViewModel = viewModel()
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "measurement") {
        composable("measurement") {
            MeasurementScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
