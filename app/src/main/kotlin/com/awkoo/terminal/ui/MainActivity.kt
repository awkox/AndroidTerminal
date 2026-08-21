package com.awkoo.terminal.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.flowWithLifecycle
import com.awkoo.terminal.ui.compose.SessionListDrawer
import com.awkoo.terminal.ui.compose.SessionViewScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 终端模拟器主 Activity。
 *
 * 承载 Compose UI，管理会话列表抽屉和终端视图。
 * 当所有会话关闭时自动结束 Activity。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MainScreen()
        }

        viewModel.sessionListState
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .filter { it.isEmpty() }
            .drop(1)
            .onEach { finish() }
            .launchIn(lifecycleScope)
    }

    @Composable
    @Preview
    fun MainScreen() {
        MaterialTheme(
            colorScheme = darkColorScheme()
        ) {
            SessionListDrawer { innerPadding, terminalViewRef, modifierState ->
                SessionViewScreen(
                    innerPadding = innerPadding,
                    terminalViewRef = terminalViewRef,
                    modifierState = modifierState
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
