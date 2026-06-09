package me.obrekht.wishu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.obrekht.wishu.ui.SettingsScreen
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
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onNavigateBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
