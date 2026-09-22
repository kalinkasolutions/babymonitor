package ch.lqy.babyphone.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.lqy.babyphone.ui.AccountAvatar
import ch.lqy.babyphone.ui.MonitorScreen
import ch.lqy.babyphone.ui.account.AccountScreen
import ch.lqy.babyphone.ui.devices.ConnectScreen
import ch.lqy.babyphone.ui.devices.DevicesScreen
import ch.lqy.babyphone.ui.settings.SettingsScreen

/**
 * Everything behind the sign-in. The bottom bar carries the three places you switch between;
 * Account and Connect are pushed over it, hiding the bar, because each is a task you finish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignedInNavHost(
    username: String,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onSignedOut: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = Tab.entries.firstOrNull { tab ->
        destination?.hierarchy?.any { it.hasRoute(tab.destination::class) } == true
    }

    Scaffold(
        topBar = {
            if (currentTab != null) {
                TopAppBar(
                    title = { Text(currentTab.label) },
                    actions = {
                        AccountAvatar(username) { navController.navigate(Destination.Account) }
                    }
                )
            }
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    for (tab in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { navController.switchTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { insets ->
        NavHost(
            navController = navController,
            startDestination = Destination.Monitor,
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
        ) {
            composable<Destination.Monitor> {
                MonitorScreen(onGoToDevices = { navController.switchTab(Tab.Devices) })
            }

            composable<Destination.Devices> {
                DevicesScreen(onConnect = { navController.navigate(Destination.Connect) })
            }

            composable<Destination.Settings> {
                SettingsScreen(serverUrl, onServerUrlChange)
            }

            composable<Destination.Account> {
                AccountScreen(
                    onClose = { navController.popBackStack() },
                    onSignedOut = onSignedOut
                )
            }

            composable<Destination.Connect> {
                ConnectScreen(
                    onBack = { navController.popBackStack() },
                    // The invite form lives in the account area; pairing by email and pairing by
                    // code are the same decision, so Connect is the honest place to offer it from.
                    onInvite = { navController.navigate(Destination.Account) }
                )
            }
        }
    }
}

/**
 * Tab switching, not stacking: pop back to the start destination and keep each tab's own state,
 * so the bar never builds a back stack of tabs.
 */
private fun NavHostController.switchTab(tab: Tab) {
    navigate(tab.destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
