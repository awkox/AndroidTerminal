package com.awkoo.terminal.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awkoo.terminal.ui.MainActivity
import kotlinx.coroutines.launch

@Composable
fun MainActivity.SessionListScreen(drawerState: DrawerState) {
    val scope = rememberCoroutineScope()
    val sessionList by viewModel.sessionListState.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier
            .statusBarsPadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = sessionList,
                key = { it.id },
            ) { session ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            val title by session.titleState.collectAsStateWithLifecycle()
                            val sessionName by session.sessionName.collectAsStateWithLifecycle()
                            val currentTitle = title
                            Text("[${session.id}] $sessionName")
                            if (!currentTitle.isNullOrEmpty()) {
                                Text(currentTitle)
                            }
                        }
                    },
                    selected = session == currentSession,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            viewModel.setCurrentSession(session.id)
                        }
                    },
                    shape = MaterialTheme.shapes.medium
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text("New Session") },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            viewModel.addSession(null)
                        }
                    },
                    icon = {
                        Icon(Icons.Default.Add, null)
                    },
                    shape = MaterialTheme.shapes.medium
                )
            }
        }
    }
}
