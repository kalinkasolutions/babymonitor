package ch.kalinka.babymonitor.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.ui.platform.LocalContext
import ch.kalinka.babymonitor.device.LocalSession
import ch.kalinka.babymonitor.ui.AccountAvatar
import ch.kalinka.babymonitor.ui.MonitorScreen
import ch.kalinka.babymonitor.ui.account.AccountScreen
import ch.kalinka.babymonitor.ui.devices.ConnectScreen
import ch.kalinka.babymonitor.ui.devices.DevicesScreen
import ch.kalinka.babymonitor.ui.settings.SettingsScreen

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
                        // With no account behind it the account screens have nothing to show —
                        // they ask the server who you are — so the avatar offers the one thing
                        // that is missing instead, and goes to the sign-in screen.
                        val withoutAccount = LocalSession(LocalContext.current).enabled
                        AccountAvatar(username) {
                            if (withoutAccount) onSignedOut() else navController.navigate(Destination.Account)
                        }
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

                // Says the padding above has already dealt with the status bar. Without it a
                // screen pushed over this one — Connect, Account — puts its own bar below a
                // second copy of the same inset, and the gap is twice what it should be.
                .consumeWindowInsets(insets)
        ) {
            composable<Destination.Monitor> {
                MonitorScreen(onGoToDevices = { navController.switchTab(Tab.Devices) })
            }

            composable<Destination.Devices> {
                DevicesScreen(onConnect = { navController.navigate(Destination.Connect) })
            }

            composable<Destination.Settings> {
                SettingsScreen(serverUrl, onServerUrlChange, onSignedOut)
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
