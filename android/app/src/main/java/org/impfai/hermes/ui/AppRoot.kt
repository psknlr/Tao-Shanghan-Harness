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
import org.impfai.hermes.ui.features.DifferentialScreen
import org.impfai.hermes.ui.features.MistreatScreen
import org.impfai.hermes.ui.features.TeachScreen
import org.impfai.hermes.ui.features.TraceScreen
import org.impfai.hermes.ui.library.LibraryScreen
import org.impfai.hermes.ui.library.ReaderScreen
import org.impfai.hermes.ui.research.ResearchScreen
import org.impfai.hermes.ui.search.SearchScreen
import org.impfai.hermes.ui.settings.SettingsScreen
import org.impfai.hermes.ui.skills.SkillsScreen

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
                    onOpenSkills = { navController.navigate("skills") },
                    onOpenFeature = { navController.navigate(it) },
                )
            }
            composable(
                route = "search?query={query}&channel={channel}",
                arguments = listOf(
                    navArgument("query") { defaultValue = "" },
                    navArgument("channel") { defaultValue = "" },
                ),
            ) { entry ->
                SearchScreen(
                    onOpenClause = { navController.openClause(it) },
                    initialQuery = entry.arguments?.getString("query") ?: "",
                    initialChannel = entry.arguments?.getString("channel") ?: "",
                )
            }
            composable("match") {
                MatchScreen(onOpenClause = { navController.openClause(it) })
            }
            composable(
                route = "agent?prefill={prefill}",
                arguments = listOf(navArgument("prefill") { defaultValue = "" }),
            ) { entry ->
                AgentScreen(
                    onOpenClause = { navController.openClause(it) },
                    prefill = entry.arguments?.getString("prefill") ?: "",
                )
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
                    onAskAi = { question ->
                        navController.navigate(
                            "agent?prefill=${android.net.Uri.encode(question)}")
                    },
                    onOpenBook = { book, locate ->
                        navController.navigate(
                            "reader?title=${android.net.Uri.encode(book)}" +
                                "&section=&locate=${android.net.Uri.encode(locate)}")
                    },
                )
            }
            composable("skills") {
                SkillsScreen(onBack = { navController.popBackStack() })
            }
            composable("teach") {
                TeachScreen(onOpenClause = { navController.openClause(it) },
                    onBack = { navController.popBackStack() })
            }
            composable("differential") {
                DifferentialScreen(onOpenClause = { navController.openClause(it) },
                    onBack = { navController.popBackStack() })
            }
            composable("mistreat") {
                MistreatScreen(onOpenClause = { navController.openClause(it) },
                    onBack = { navController.popBackStack() })
            }
            composable("research") {
                ResearchScreen(onOpenClause = { navController.openClause(it) },
                    onBack = { navController.popBackStack() })
            }
            composable("trace") {
                TraceScreen(onOpenClause = { navController.openClause(it) },
                    onBack = { navController.popBackStack() })
            }
            composable("library") {
                LibraryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId, section ->
                        navController.navigate(
                            "reader?title=${android.net.Uri.encode(bookId)}" +
                                "&section=${android.net.Uri.encode(section)}")
                    },
                )
            }
            composable(
                route = "reader?title={title}&section={section}&locate={locate}",
                arguments = listOf(
                    navArgument("title") { defaultValue = "" },
                    navArgument("section") { defaultValue = "" },
                    navArgument("locate") { defaultValue = "" },
                ),
            ) { entry ->
                ReaderScreen(
                    titleOrId = entry.arguments?.getString("title") ?: "",
                    initialSection = entry.arguments?.getString("section") ?: "",
                    locateText = entry.arguments?.getString("locate") ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
