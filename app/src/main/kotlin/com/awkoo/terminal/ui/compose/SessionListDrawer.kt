package com.awkoo.terminal.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.awkoo.terminal.extrakeys.ExtraKeyActions
import com.awkoo.terminal.extrakeys.ExtraKeyDispatcher
import com.awkoo.terminal.extrakeys.ExtraKeysBar
import com.awkoo.terminal.extrakeys.ExtraKeysConfig
import com.awkoo.terminal.extrakeys.ExtraKeysModifierState
import com.awkoo.terminal.ui.MainActivity
import com.awkoo.terminal.ui.view.TerminalView
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
@Composable
fun MainActivity.SessionListDrawer(
    content: @Composable (
        innerPadding: PaddingValues,
        terminalViewRef: androidx.compose.runtime.MutableState<TerminalView?>,
        modifierState: ExtraKeysModifierState
    ) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val modifierState = remember { ExtraKeysModifierState() }

    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            terminalViewRef.value?.stopTextSelectionMode()
        }
    }

    val dispatcher = remember {
        ExtraKeyDispatcher(
            modifierState = modifierState,
            actions = object : ExtraKeyActions {
                override fun onToggleKeyboard() {
                    terminalViewRef.value?.toggleIme()
                }

                override fun onToggleDrawer() {
                    scope.launch {
                        if (drawerState.isClosed) drawerState.open()
                        else drawerState.close()
                    }
                }

                override fun onPaste() {
                    terminalViewRef.value?.pasteTextFromClipboard()
                }

                override fun onToggleScroll() {
                    terminalViewRef.value?.mEmulator?.toggleAutoScrollDisabled()
                }
            },
            terminalViewProvider = { terminalViewRef.value }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || drawerState.isAnimationRunning,
        drawerContent = {
            SessionListScreen(drawerState = drawerState)
        },
        content = {
            Scaffold(
                topBar = {
                    TopBar(drawerState)
                },
                bottomBar = {
                    ExtraKeysBar(
                        // TODO：持久化存储，目前读取默认值
                        config = ProtoBuf.decodeFromByteArray<ExtraKeysConfig>(byteArrayOf()),
                        modifierState = modifierState,
                        onDispatch = { dispatcher.dispatch(it) }
                    )
                }
            ) { innerPadding ->
                content(innerPadding, terminalViewRef, modifierState)
            }
        }
    )
}
