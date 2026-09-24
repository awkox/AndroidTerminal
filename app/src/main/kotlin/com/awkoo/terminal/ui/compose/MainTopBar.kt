package com.awkoo.terminal.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.window.PopupProperties
import com.awkoo.terminal.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    title: String?,
    running: Boolean,
    onNavigationClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            if (!title.isNullOrEmpty()) {
                Text(
                    title,
                    color = if (running) Color.Unspecified else MaterialTheme.colorScheme.error,
                    textDecoration = if (running) TextDecoration.None else TextDecoration.LineThrough
                )
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
            // 菜单所在弹窗设为不可聚焦：避免弹窗抢占窗口焦点导致软键盘收起。
            // BackHandler 兜底关闭（不可聚焦弹窗不再接收返回键）。
            BackHandler(enabled = menuMoreExpanded) { menuMoreExpanded = false }
            IconButton(
                onClick = { menuMoreExpanded = true }
            ) {
                Icon(Icons.Default.MoreVert, null)
            }
            DropdownMenu(
                expanded = menuMoreExpanded,
                onDismissRequest = { menuMoreExpanded = false },
                properties = PopupProperties(focusable = false)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_settings)) },
                    onClick = {
                        menuMoreExpanded = false
                        onSettingsClick()
                    }
                )
            }
        }
    )
}
