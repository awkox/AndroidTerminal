package com.awkoo.terminal.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    title: String?,
    onNavigationClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            if (!title.isNullOrEmpty()) {
                Text(title)
            }
        },
        modifier = Modifier,
        navigationIcon = {
            IconButton(
                onClick = onNavigationClick
            ) {
                Icon(Icons.Default.Menu, null)
            }
        },
        actions = {
            var menuMoreExpanded by remember { mutableStateOf(false) }
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
                    text = { Text("Settings") },
                    onClick = {
                        menuMoreExpanded = false
                        onSettingsClick()
                    }
                )
            }
        }
    )
}
