package com.nexastream.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexastream.app.navigation.Screen

@Composable
fun NexastreamBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = Color.Black.copy(alpha = 0.95f),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        modifier = Modifier.height(65.dp)
    ) {
        val items = listOf(
            Triple(Screen.Home, Icons.Outlined.Home, Icons.Filled.Home),
            Triple(Screen.Search, Icons.Outlined.Search, Icons.Filled.Search),
            Triple(Screen.Movies, Icons.Outlined.PlayArrow, Icons.Filled.PlayArrow),
            Triple(Screen.Providers, Icons.Outlined.List, Icons.Filled.List),
        )

        items.forEach { (screen, icon, selectedIcon) ->
            val isSelected = currentRoute == screen.route
            NavigationBarItem(
                icon = { 
                    Icon(
                        if (isSelected) selectedIcon else icon, 
                        contentDescription = screen.route,
                        modifier = Modifier.size(24.dp)
                    ) 
                },
                label = { 
                    Text(
                        screen.route.replaceFirstChar { it.uppercase() }, 
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ) 
                },
                selected = isSelected,
                onClick = { onNavigate(screen.route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    unselectedIconColor = Color.Gray,
                    selectedTextColor = Color.White,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun NexastreamTopBar(
    alpha: Float = 0f,
    onCategoryClick: (String) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.7f * (1f - alpha) + alpha),
                        Color.Black.copy(alpha = 0.3f * (1f - alpha) + alpha * 0.8f),
                        Color.Transparent
                    )
                )
            )
            .padding(top = 32.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "N",
                color = Color.Red,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                modifier = Modifier.padding(end = 24.dp)
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.weight(1f)
            ) {
                TopBarLink("TV Shows") { onCategoryClick("TV Shows") }
                TopBarLink("Movies") { onCategoryClick("Movies") }
                TopBarLink("Categories") { onCategoryClick("Categories") }
            }
        }
    }
}

@Composable
private fun TopBarLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
