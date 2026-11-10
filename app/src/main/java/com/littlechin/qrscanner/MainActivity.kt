package com.littlechin.qrscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.littlechin.qrscanner.ui.DetailScreen
import com.littlechin.qrscanner.ui.HomeScreen
import com.littlechin.qrscanner.ui.ScanScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                AppNav()
            }
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onScan = { nav.navigate("scan") },
                onContinuousScan = { nav.navigate("scan/continuous") },
                onOpenRecord = { id -> nav.navigate("detail/$id") },
            )
        }
        composable("scan") {
            ScanScreen(
                onSaved = { id ->
                    nav.navigate("detail/$id") {
                        popUpTo("home")
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("scan/continuous") {
            ScanScreen(
                continuous = true,
                onSaved = { id ->
                    nav.navigate("detail/$id") {
                        popUpTo("home")
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                recordId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
