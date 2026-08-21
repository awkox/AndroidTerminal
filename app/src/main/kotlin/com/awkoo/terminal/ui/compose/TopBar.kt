package com.awkoo.terminal.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awkoo.terminal.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivity.TopBar(drawerState: DrawerState) {
    val scope = rememberCoroutineScope()
    val currentSession by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val title by remember(currentSession) {
        currentSession?.titleState ?: MutableStateFlow(null)
    }.collectAsStateWithLifecycle()
    val currentSessionName by remember(currentSession) {
        currentSession?.sessionName ?: MutableStateFlow(null)
    }.collectAsStateWithLifecycle()
    var menuMoreExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(title ?: currentSessionName ?: "null session")
        },
        modifier = Modifier,
        navigationIcon = {
            IconButton(
                onClick = {
                    scope.launch {
                        if (drawerState.isClosed) {
                            drawerState.open()
                        } else {
                            drawerState.close()
                        }
                    }
                }
            ) {
                Icon(Icons.Default.Menu, null)
            }
        },
        actions = {
            IconButton(
                onClick = { menuMoreExpanded = true }
            ) {
                Icon(Icons.Default.MoreVert, null)
            }
            DropdownMenu(
                expanded = menuMoreExpanded,
                onDismissRequest = { menuMoreExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("设置") },
                    onClick = {
                        menuMoreExpanded = false
                    }
                )
            }
        }
    )
}
