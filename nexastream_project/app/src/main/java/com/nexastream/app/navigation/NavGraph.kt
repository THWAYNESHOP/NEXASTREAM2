package com.nexastream.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nexastream.app.ui.screens.home.HomeScreen
import com.nexastream.app.ui.screens.details.DetailScreen
import com.nexastream.app.ui.screens.downloads.DownloadsScreen
import com.nexastream.app.ui.screens.player.PlayerScreen
import com.nexastream.app.ui.screens.providers.ProvidersScreen
import com.nexastream.app.ui.screens.search.SearchScreen
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.UserPreferences

@Composable
fun NavGraph(navController: NavHostController) {
    val startDestination = if (UserPreferences.currentProvider == null) {
        Screen.Providers.route
    } else {
        Screen.Home.route
    }
    
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Providers.route) {
            ProvidersScreen(
                onProviderSelected = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Providers.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Screen.Home.route) {
            HomeScreen(
                onMovieClick = { id ->
                    navController.navigate(Screen.Details.createRoute(id))
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onMovieClick = { id ->
                    navController.navigate(Screen.Details.createRoute(id))
                }
            )
        }

        composable(Screen.Movies.route) {
            // Reusing HomeScreen with a filter or similar if needed, 
            // but for now let's just point to home or a placeholder
            HomeScreen(onMovieClick = { id -> navController.navigate(Screen.Details.createRoute(id)) })
        }

        composable(Screen.TvShows.route) {
            HomeScreen(onMovieClick = { id -> navController.navigate(Screen.Details.createRoute(id)) })
        }

        composable(Screen.Downloads.route) {
            if (Provider.supportsDownloads(UserPreferences.currentProvider)) {
                DownloadsScreen(
                    onPlayClick = { id ->
                        navController.navigate(Screen.Player.createRoute(id))
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Downloads.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
        
        composable(
            route = Screen.Details.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) {
            DetailScreen(
                onPlayClick = { id ->
                    navController.navigate(Screen.Player.createRoute(id))
                },
                onShowClick = { id ->
                    navController.navigate(Screen.Details.createRoute(id))
                }
            )
        }
        
        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}
