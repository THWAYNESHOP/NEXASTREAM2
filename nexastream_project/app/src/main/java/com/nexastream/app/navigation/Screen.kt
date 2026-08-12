package com.nexastream.app.navigation

import java.net.URLEncoder

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Movies : Screen("movies")
    object TvShows : Screen("tv_shows")
    object Details : Screen("details/{id}") {
        fun createRoute(id: String) = "details/${URLEncoder.encode(id, "UTF-8")}"
    }
    object Player : Screen("player/{id}") {
        fun createRoute(id: String) = "player/${URLEncoder.encode(id, "UTF-8")}"
    }
    object Search : Screen("search")
    object Providers : Screen("providers")
}
