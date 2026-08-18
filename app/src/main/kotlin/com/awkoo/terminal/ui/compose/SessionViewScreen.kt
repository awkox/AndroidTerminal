package com.awkoo.terminal.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Typeface
import com.awkoo.terminal.extrakeys.ExtraKeysModifierState
import com.awkoo.terminal.ui.MainActivity
import com.awkoo.terminal.ui.view.ExtraKeysModifierSnapshot
import com.awkoo.terminal.ui.view.TerminalView

@Composable
fun MainActivity.SessionViewScreen(
    innerPadding: PaddingValues,
    terminalViewRef: MutableState<TerminalView?>,
    modifierState: ExtraKeysModifierState
) {
    val currentSession by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()

    AndroidView(
        modifier = Modifier
            .padding(innerPadding)
            .imePadding()
            .fillMaxSize(),
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

                it.typeface = Typeface.createFromAsset(context.assets, "font/maplemononl_nf_cn_regular.otf")

                terminalViewRef.value = it
            }
        },
        update = {
            if (currentSession != it.currentSession)
                it.currentSession = currentSession
            if (fontSize != it.textSize)
                it.textSize = fontSize
        },
        onRelease = { view ->
            view.dispose()
            if (terminalViewRef.value === view) {
                terminalViewRef.value = null
            }
        }
    )
}
