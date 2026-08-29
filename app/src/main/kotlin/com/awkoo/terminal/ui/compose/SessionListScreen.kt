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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awkoo.libterminal.engine.TerminalSession

@Composable
fun SessionListScreen(
    sessionList: List<TerminalSession>,
    currentSession: TerminalSession?,
    onSessionSelected: (sessionId: Int) -> Unit,
    onNewSession: () -> Unit
) {
    val listState = rememberLazyListState()

    ModalDrawerSheet(
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
                            val currentTitle = title ?: sessionName
                            if (!currentTitle.isNullOrEmpty()) {
                                Text("[${session.id}] $currentTitle")
                            }
                        }
                    },
                    selected = session == currentSession,
                    onClick = { onSessionSelected(session.id) },
                    shape = MaterialTheme.shapes.medium
                )
            }
            item {
                NavigationDrawerItem(
                    label = { Text("New Session") },
                    selected = false,
                    onClick = onNewSession,
                    icon = {
                        Icon(Icons.Default.Add, null)
                    },
                    shape = MaterialTheme.shapes.medium
                )
            }
        }
    }
}
