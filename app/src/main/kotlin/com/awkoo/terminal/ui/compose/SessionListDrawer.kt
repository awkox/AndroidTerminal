package com.awkoo.terminal.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Typeface
import android.view.KeyEvent
import com.awkoo.terminal.R
import com.awkoo.terminal.extrakeys.ExtraKeyActions
import com.awkoo.terminal.extrakeys.ExtraKeyDispatcher
import com.awkoo.terminal.extrakeys.ExtraKeysBar
import com.awkoo.terminal.extrakeys.ExtraKeysConfig
import com.awkoo.terminal.extrakeys.ExtraKeysModifierState
import com.awkoo.terminal.ui.MainActivity
import com.awkoo.terminal.ui.settings.SettingsNavHost
import com.awkoo.libterminal.view.TerminalView
import com.awkoo.libterminal.view.ExtraKeysModifierSnapshot
import com.awkoo.libterminal.view.interact.ActionModeCustomizer
import com.awkoo.libterminal.engine.TerminalCursorStyle
import com.awkoo.libterminal.color.TerminalColorScheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@OptIn(ExperimentalSerializationApi::class)
@Composable
fun MainActivity.SessionListDrawer(colorScheme: TerminalColorScheme) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val modifierState = remember { ExtraKeysModifierState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

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

    // 应用级 IME 策略：终端仅在作为活跃界面时才显示键盘。
    // 覆盖层（设置页）或抽屉打开时收起，避免"进入设置自动弹键盘"；
    // 新会话（首次绑定）且终端活跃时才自动拉起，取代 lib 内绑定时无条件 toggleIme(true)。
    val terminalActive = !showSettings && drawerState.isClosed
    LaunchedEffect(terminalActive) {
        if (!terminalActive) {
            terminalViewRef.value?.hideIme()
        }
    }
    LaunchedEffect(sessionList.size) {
        if (sessionList.isNotEmpty() && terminalActive) {
            terminalViewRef.value?.toggleIme(true)
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

    Box(modifier = Modifier.fillMaxSize()) {
        // 终端界面常驻底部，设置页以覆盖层形式在其上方滑入/滑出，
        // 避免 AnimatedContent 同时组合两侧导致 TerminalView 反复重建（卡顿/白闪）
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
                            onSettingsClick = { showSettings = true }
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
                        colorScheme = colorScheme,
                        cursorStyle = cursorStyle,
                        cursorBlinking = cursorBlinking,
                        textBlinking = textBlinking,
                    )
                }
            }
        )

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            label = "settings-overlay"
        ) {
            SettingsNavHost(onExit = { showSettings = false }, viewModel = viewModel)
        }
    }
}

@Composable
private fun MainActivity.SessionViewScreen(
    innerPadding: PaddingValues,
    terminalViewRef: MutableState<TerminalView?>,
    modifierState: ExtraKeysModifierState,
    colorScheme: TerminalColorScheme,
    cursorStyle: TerminalCursorStyle,
    cursorBlinking: Boolean,
    textBlinking: Boolean
) {
    val currentSession by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()

    // 复制/粘贴浮标菜单文案由 app 侧资源本地化（locale 变化后重组时重建）
    val copyLabel = stringResource(R.string.action_copy)
    val pasteLabel = stringResource(R.string.action_paste)
    val actionModeCustomizer = remember(copyLabel, pasteLabel) {
        object : ActionModeCustomizer() {
            override fun copyText() = copyLabel
            override fun pasteText() = pasteLabel
        }
    }

    AndroidView(
        modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
            .clipToBounds(),
        factory = { context ->
            TerminalView(context).also {
                it.isFocusable = true
                it.isFocusableInTouchMode = true

                it.actionModeCustomizer = actionModeCustomizer

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
            if (actionModeCustomizer != it.actionModeCustomizer)
                it.actionModeCustomizer = actionModeCustomizer
            if (currentSession != it.currentSession)
                it.currentSession = currentSession
            if (fontSize != it.textSize)
                it.textSize = fontSize
            if (colorScheme != it.colorScheme)
                it.colorScheme = colorScheme
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
