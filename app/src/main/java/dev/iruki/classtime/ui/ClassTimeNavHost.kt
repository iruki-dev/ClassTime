package dev.iruki.classtime.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Mic
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.iruki.classtime.ui.home.HomeScreen
import dev.iruki.classtime.util.SetupIssue
import dev.iruki.classtime.ui.recordings.RecordingsScreen
import dev.iruki.classtime.ui.term.TermScreen
import dev.iruki.classtime.ui.timetable.CourseEditScreen
import dev.iruki.classtime.ui.timetable.TimetableScreen

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Dest("home", "녹음", Icons.Filled.Mic)
    data object Timetable : Dest("timetable", "시간표", Icons.Filled.CalendarMonth)
    data object Recordings : Dest("recordings", "녹음 목록", Icons.AutoMirrored.Filled.List)
}

private val bottomDests = listOf(Dest.Home, Dest.Timetable, Dest.Recordings)

@Composable
fun ClassTimeNavHost(
    setupIssues: List<SetupIssue>,
    onResolveIssue: (SetupIssue) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in bottomDests.map { it.route }) {
                NavigationBar {
                    bottomDests.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
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
            }
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Dest.Home.route,
            modifier = Modifier.padding(inner),
        ) {
            composable(Dest.Home.route) {
                HomeScreen(
                    setupIssues = setupIssues,
                    onResolveIssue = onResolveIssue,
                    onOpenTimetable = { navController.navigate(Dest.Timetable.route) },
                )
            }
            composable(Dest.Timetable.route) {
                TimetableScreen(
                    onAddCourse = { navController.navigate("course_edit") },
                    onEditCourse = { groupId -> navController.navigate("course_edit?groupId=$groupId") },
                    onOpenTerm = { navController.navigate("term") },
                )
            }
            composable(Dest.Recordings.route) {
                RecordingsScreen()
            }
            composable(
                route = "course_edit?groupId={groupId}",
                arguments = listOf(
                    androidx.navigation.navArgument("groupId") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
            ) { entry ->
                CourseEditScreen(
                    groupId = entry.arguments?.getString("groupId"),
                    onDone = { navController.popBackStack() },
                )
            }
            composable("term") {
                TermScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
