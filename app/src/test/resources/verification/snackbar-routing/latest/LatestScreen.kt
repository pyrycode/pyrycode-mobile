package fixture

import androidx.compose.material3.SnackbarHostState

suspend fun latestScreen(host: SnackbarHostState) {
    host.showSnackbar("Try again")
}
