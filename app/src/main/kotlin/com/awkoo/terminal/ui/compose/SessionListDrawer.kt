package com.awkoo.terminal.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Typeface
import android.view.KeyEvent
import com.awkoo.terminal.extrakeys.ExtraKeyActions
import com.awkoo.terminal.extrakeys.ExtraKeyDispatcher
import com.awkoo.terminal.extrakeys.ExtraKeysBar
import com.awkoo.terminal.extrakeys.ExtraKeysConfig
import com.awkoo.terminal.extrakeys.ExtraKeysModifierState
import com.awkoo.terminal.ui.MainActivity
import com.awkoo.libterminal.view.TerminalView
import com.awkoo.libterminal.view.ExtraKeysModifierSnapshot
import com.awkoo.libterminal.engine.TerminalCursorStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
@Composable
fun MainActivity.SessionListDrawer(useLightTheme: Boolean) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val modifierState = remember { ExtraKeysModifierState() }

    val currentSession by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val sessionList by viewModel.sessionListState.collectAsStateWithLifecycle()
    val cursorStyle by viewModel.terminalCursorStyle.collectAsStateWithLifecycle()
    val cursorBlinking by viewModel.cursorBlinking.collectAsStateWithLifecycle()
    val textBlinking by viewModel.textBlinking.collectAsStateWithLifecycle()

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
                    terminalViewRef.value?.toggleAutoScrollDisabled()
                }

                override fun sendKeyDown(keyCode: Int, event: KeyEvent) {
                    terminalViewRef.value?.onKeyDown(keyCode, event) 
                }

                override fun sendCodePoint(codePoint: Int, ctrlDown: Boolean, altDown: Boolean) {
                    terminalViewRef.value?.inputVirtualKeyCodePoint(codePoint, ctrlDown, altDown)
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || drawerState.isAnimationRunning,
        modifier = Modifier.imePadding(),
        drawerContent = {
            SessionListScreen(
                sessionList = sessionList,
                currentSession = currentSession,
                onSessionSelected = { id ->
                    scope.launch {
                        drawerState.close()
                        viewModel.setCurrentSession(id)
                    }
                },
                onNewSession = {
                    scope.launch {
                        drawerState.close()
                        viewModel.addSession(null)
                    }
                }
            )
        },
        content = {
            Scaffold(
                topBar = {
                    val currentSessionTitle by remember(currentSession) {
                        currentSession?.titleState ?: MutableStateFlow(null)
                    }.collectAsStateWithLifecycle()
                    val currentSessionName by remember(currentSession) {
                        currentSession?.sessionName ?: MutableStateFlow(null)
                    }.collectAsStateWithLifecycle()
                    MainTopBar(
                        title = currentSessionTitle ?: currentSessionName,
                        onNavigationClick = {
                            scope.launch {
                                if (drawerState.isClosed) {
                                    drawerState.open()
                                } else {
                                    drawerState.close()
                                }
                            }
                        },
                        onSettingsClick = {}
                    )
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
                SessionViewScreen(
                    innerPadding = innerPadding,
                    terminalViewRef = terminalViewRef,
                    modifierState = modifierState,
                    useLightTheme = useLightTheme,
                    cursorStyle = cursorStyle,
                    cursorBlinking = cursorBlinking,
                    textBlinking = textBlinking,
                )
            }
        }
    )
}

@Composable
private fun MainActivity.SessionViewScreen(
    innerPadding: PaddingValues,
    terminalViewRef: MutableState<TerminalView?>,
    modifierState: ExtraKeysModifierState,
    useLightTheme: Boolean,
    cursorStyle: TerminalCursorStyle,
    cursorBlinking: Boolean,
    textBlinking: Boolean
) {
    val currentSession by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()

    AndroidView(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
            .clipToBounds(),
        factory = { context ->
            TerminalView(context).also {
                it.isFocusable = true
                it.isFocusableInTouchMode = true

                // 物理键盘输入也遵守粘性 Ctrl/Alt/Shift/Fn 切换
                it.extraKeysModifierReader = {
                    ExtraKeysModifierSnapshot(
                        ctrl = modifierState.readCtrl(),
                        alt = modifierState.readAlt(),
                        shift = modifierState.readShift(),
                        fn = modifierState.readFn()
                    )
                }

                it.typeface = Typeface.createFromAsset(
                    context.assets,
                    "font/maplemononl_nf_cn_regular.otf"
                )

                terminalViewRef.value = it
            }
        },
        update = {
            if (currentSession != it.currentSession)
                it.currentSession = currentSession
            if (fontSize != it.textSize)
                it.textSize = fontSize
            if (useLightTheme != it.useLightTheme)
                it.useLightTheme = useLightTheme
            if (cursorStyle != it.cursorStyle)
                it.cursorStyle = cursorStyle
            if (cursorBlinking != it.cursorBlinking)
                it.cursorBlinking = cursorBlinking
            if (textBlinking != it.textBlinking)
                it.textBlinking = textBlinking
        },
        onRelease = { view ->
            view.dispose()
            if (terminalViewRef.value === view) {
                terminalViewRef.value = null
            }
        }
    )
}
