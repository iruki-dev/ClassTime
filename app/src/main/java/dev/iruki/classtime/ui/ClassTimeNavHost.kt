package dev.iruki.classtime.ui

import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalContext
import dev.iruki.classtime.audio.PlaybackController
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.SystemScreens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.iruki.classtime.R
import dev.iruki.classtime.ui.home.HomeScreen
import dev.iruki.classtime.ui.player.MiniPlayer
import dev.iruki.classtime.ui.player.PlayerSheet
import dev.iruki.classtime.ui.player.PlayerTab
import dev.iruki.classtime.ui.player.PlayerText
import dev.iruki.classtime.ui.ai.AiLabsScreen
import dev.iruki.classtime.ui.player.PlayerViewModel
import dev.iruki.classtime.ui.recordings.RecordingsScreen
import dev.iruki.classtime.ui.settings.SettingsScreen
import dev.iruki.classtime.ui.term.TermScreen
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.timetable.CourseEditScreen
import dev.iruki.classtime.ui.timetable.TimetableScreen
import dev.iruki.classtime.util.SetupIssue

private sealed class Dest(
    val route: String,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    data object Home : Dest("home", R.string.nav_home, Icons.Rounded.Today, Icons.Outlined.Today)
    data object Timetable :
        Dest("timetable", R.string.nav_timetable, Icons.Rounded.CalendarViewWeek, Icons.Outlined.CalendarViewWeek)
    data object Recordings :
        Dest("recordings", R.string.nav_recordings, Icons.Rounded.GraphicEq, Icons.Outlined.GraphicEq)
}

private val tabs = listOf(Dest.Home, Dest.Timetable, Dest.Recordings)

private const val ROUTE_COURSE_EDIT = "course_edit?groupId={groupId}"
private const val ROUTE_TERM = "term"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_AI = "ai"

@Composable
fun ClassTimeNavHost(
    setupIssues: List<SetupIssue>,
    onResolveIssue: (SetupIssue) -> Unit,
    openTranscript: Long? = null,
    onTranscriptOpened: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTab = currentRoute in tabs.map { it.route }

    // 재생은 앱 전체가 공유한다. 미니 플레이어는 모든 탭의 하단 탭 바로 위에 뜬다.
    val playerVm: PlayerViewModel = hiltViewModel()
    val playback by playerVm.state.collectAsStateWithLifecycle()
    val styles by playerVm.styles.collectAsStateWithLifecycle()
    val ai by playerVm.ai.collectAsStateWithLifecycle()
    val transcript by playerVm.transcript.collectAsStateWithLifecycle()
    var playerOpen by remember { mutableStateOf(false) }
    // 플레이어에서 마지막으로 본 탭. 다시 열면 그 탭으로 열린다.
    var playerTab by rememberSaveable { mutableStateOf(PlayerTab.AUDIO) }
    val nowPlaying = playback.recording

    // 재생하지 못하면 이유를 알린다. 권한 문제(들여온 파일)면 바로 허용할 수 있게.
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val noAccessText = stringResource(R.string.player_no_access)
    val unreadableText = stringResource(R.string.player_unreadable)
    val allowText = stringResource(R.string.ai_action_allow_access)
    val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) SystemScreens.openAppDetails(context)
    }
    LaunchedEffect(playerVm) {
        playerVm.problems.collect { problem ->
            val noAccess = problem == PlaybackController.Problem.NO_ACCESS
            val result = snackbar.showSnackbar(
                message = if (noAccess) noAccessText else unreadableText,
                actionLabel = if (noAccess) allowText else null,
                withDismissAction = !noAccess,
            )
            if (result == SnackbarResult.ActionPerformed) access.launch(AppPermissions.audioReadPermission())
        }
    }

    // 텍스트를 열어야 할 때(변환 알림, 목록의 ‘텍스트 보기’): 녹음을 올리고 텍스트 탭으로.
    val openText: (Long) -> Unit = { id ->
        playerVm.openForText(id) {
            playerTab = PlayerTab.TEXT
            playerOpen = true
        }
    }
    LaunchedEffect(openTranscript) {
        openTranscript?.let {
            openText(it)
            onTranscriptOpened()
        }
    }

    // 탭 화면은 바탕(page)과 같은 색의 하단 탭을 쓴다. 상단 인셋은 각 화면이, 하단 인셋은
    // 하단 탭(또는 탭이 없는 상세 화면 자신)이 처리하도록 여기서는 인셋을 쓰지 않는다.
    Scaffold(
        containerColor = AppTheme.colors.page,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (onTab && nowPlaying != null) {
                    MiniPlayer(
                        recording = nowPlaying,
                        icon = styles[nowPlaying.subject]?.icon.orEmpty(),
                        state = playback,
                        onToggle = { playerVm.toggle(nowPlaying) },
                        onOpen = { playerOpen = true },
                        onClose = playerVm::close,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    )
                }
                if (onTab) {
                    NavigationBar(containerColor = AppTheme.colors.page) {
                        tabs.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.switchTab(dest.route) },
                                icon = {
                                    Icon(if (selected) dest.selectedIcon else dest.icon, contentDescription = null)
                                },
                                label = { Text(stringResource(dest.label)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedTextColor = MaterialTheme.colorScheme.secondary,
                                ),
                            )
                        }
                    }
                }
            }
        },
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
                    onOpenTimetable = { navController.switchTab(Dest.Timetable.route) },
                    onAddCourse = { navController.navigate("course_edit") },
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                )
            }
            composable(Dest.Timetable.route) {
                TimetableScreen(
                    onAddCourse = { navController.navigate("course_edit") },
                    onEditCourse = { groupId -> navController.navigate("course_edit?groupId=$groupId") },
                    onOpenTerm = { navController.navigate(ROUTE_TERM) },
                    onOpenRecordings = { navController.switchTab(Dest.Recordings.route) },
                )
            }
            composable(Dest.Recordings.route) {
                RecordingsScreen(
                    onOpenPlayer = { playerOpen = true },
                    onOpenText = openText,
                )
            }
            composable(
                route = ROUTE_COURSE_EDIT,
                arguments = listOf(
                    navArgument("groupId") {
                        type = NavType.StringType
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
            composable(ROUTE_TERM) {
                TermScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    setupIssues = setupIssues,
                    onResolveIssue = onResolveIssue,
                    onBack = { navController.popBackStack() },
                    onOpenLabs = { navController.navigate(ROUTE_AI) },
                )
            }
            composable(ROUTE_AI) {
                AiLabsScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    if (playerOpen && nowPlaying != null) {
        PlayerSheet(
            recording = nowPlaying,
            icon = styles[nowPlaying.subject]?.icon.orEmpty(),
            state = playback,
            onDismiss = { playerOpen = false },
            onToggle = { playerVm.toggle(nowPlaying) },
            onSeek = playerVm::seekTo,
            onSeekBy = playerVm::seekBy,
            onSpeed = playerVm::setSpeed,
            onShare = { playerVm.share(nowPlaying) },
            onOpenFolder = playerVm::openFolder,
            onDelete = {
                playerOpen = false
                playerVm.delete(nowPlaying)
            },
            text = PlayerText(
                ai = ai,
                view = transcript,
                tab = playerTab,
                onTab = { playerTab = it },
                onConvert = { playerVm.convert(nowPlaying.id) },
                onCancel = { playerVm.cancelText(nowPlaying.id) },
                onRetry = { playerVm.retryText(nowPlaying.id) },
                onReconvert = { playerVm.reconvert(nowPlaying.id) },
                onDeleteText = { playerVm.deleteText(nowPlaying.id) },
                onCopy = { playerVm.copyText(nowPlaying, it) },
                onShare = { playerVm.shareText(nowPlaying, it) },
                onOpenLabs = {
                    playerOpen = false
                    navController.navigate(ROUTE_AI)
                },
            ),
        )
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
