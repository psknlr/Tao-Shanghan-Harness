package org.impfai.hermes.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.impfai.hermes.ui.agent.AgentScreen
import org.impfai.hermes.ui.clause.ClauseScreen
import org.impfai.hermes.ui.home.HomeScreen
import org.impfai.hermes.ui.match.MatchScreen
import org.impfai.hermes.ui.search.SearchScreen
import org.impfai.hermes.ui.settings.SettingsScreen

data class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val TOP_DESTINATIONS = listOf(
    TopDestination("home", "首页", Icons.Filled.Home),
    TopDestination("search", "检索", Icons.Filled.Search),
    TopDestination("match", "辨证", Icons.AutoMirrored.Filled.MenuBook),
    TopDestination("agent", "智能体", Icons.Filled.SmartToy),
    TopDestination("settings", "我的", Icons.Filled.Person),
)

fun NavHostController.openClause(clauseRef: String) {
    navigate("clause/${android.net.Uri.encode(clauseRef)}")
}

fun NavHostController.openSearch(query: String = "", channel: String = "") {
    val q = android.net.Uri.encode(query)
    val ch = android.net.Uri.encode(channel)
    navigate("search?query=$q&channel=$ch") {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TOP_DESTINATIONS.forEach { dest ->
                    val selected = currentRoute?.startsWith(dest.route) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            // consumeWindowInsets：Scaffold 已吃掉的底部插入不再被子級
            // imePadding 重複計入（審查發現 #13：輸入欄與鍵盤間死空隙）
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("home") {
                HomeScreen(
                    onOpenSearch = { q, ch -> navController.openSearch(q, ch) },
                    onOpenClause = { navController.openClause(it) },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable(
                route = "search?query={query}&channel={channel}",
                arguments = listOf(
                    navArgument("query") { defaultValue = "" },
                    navArgument("channel") { defaultValue = "" },
                ),
            ) {
                SearchScreen(onOpenClause = { navController.openClause(it) })
            }
            composable("match") {
                MatchScreen(onOpenClause = { navController.openClause(it) })
            }
            composable("agent") {
                AgentScreen(onOpenClause = { navController.openClause(it) })
            }
            composable("settings") {
                SettingsScreen()
            }
            composable("clause/{ref}") { entry ->
                val ref = entry.arguments?.getString("ref") ?: ""
                ClauseScreen(
                    clauseRef = ref,
                    onOpenClause = { navController.openClause(it) },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
