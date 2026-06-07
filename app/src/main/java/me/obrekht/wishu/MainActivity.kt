package me.obrekht.wishu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.obrekht.wishu.ui.ModelsScreen
import me.obrekht.wishu.ui.ReasoningScreen
import me.obrekht.wishu.ui.SettingsScreen
import me.obrekht.wishu.ui.TemperatureScreen
import me.obrekht.wishu.ui.WishuTheme
import me.obrekht.wishu.ui.WishlistScreen

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default auto styles: system bar icons follow light/dark theme.
        enableEdgeToEdge()
        setContent {
            WishuTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "wishlist") {
                    composable("wishlist") {
                        WishlistScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenReasoning = { navController.navigate("reasoning") },
                            onOpenTemperature = { navController.navigate("temperature") },
                            onOpenModels = { navController.navigate("models") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("reasoning") {
                        ReasoningScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("temperature") {
                        TemperatureScreen(onNavigateBack = { navController.popBackStack() })
                    }
                    composable("models") {
                        ModelsScreen(onNavigateBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
