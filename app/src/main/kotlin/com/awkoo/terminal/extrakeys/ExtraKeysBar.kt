package com.awkoo.terminal.extrakeys

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val BUTTON_HEIGHT = 40.dp
/** 按键自动重复间隔（毫秒）。 */
private const val REPEAT_DELAY_MS = 80L

@Composable
fun ExtraKeysBar(
    config: ExtraKeysConfig,
    modifierState: ExtraKeysModifierState,
    onDispatch: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (row in config.rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BUTTON_HEIGHT),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (button in row.buttons) {
                    ExtraKeyButtonItem(
                        button = button,
                        modifierState = modifierState,
                        onDispatch = onDispatch,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButtonItem(
    button: ExtraKeyButton,
    modifierState: ExtraKeysModifierState,
    onDispatch: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var showPopupMenu by remember { mutableStateOf(false) }

    val isModifier = button.key is ExtraKey.SpecialKey &&
        (button.key.type == SpecialKeyType.CTRL ||
            button.key.type == SpecialKeyType.ALT ||
            button.key.type == SpecialKeyType.SHIFT ||
            button.key.type == SpecialKeyType.FN)

    val isRepetitive = button.key is ExtraKey.Key &&
        button.key.value in REPETITIVE_KEYS

    val modifierType = (button.key as? ExtraKey.SpecialKey)?.type
    val isModifierActive = modifierType != null && modifierState.isSpecialKeyActive(modifierType)
    val isModifierLocked = modifierType != null && modifierState.isSpecialLocked(modifierType)

    val textColor = when {
        isModifierActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val bgColor = when {
        isModifierActive && isModifierLocked -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        isModifierActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .height(BUTTON_HEIGHT)
            .background(bgColor)
            .pointerInput(button) {
                var repeatJob: Job? = null
                detectTapGestures(
                    onPress = {
                        try {
                            tryAwaitRelease()
                        } finally {
                            repeatJob?.cancel()
                            repeatJob = null
                        }
                    },
                    onTap = {
                        onDispatch(button.key)
                    },
                    onLongPress = {
                        when {
                            isModifier && modifierType != null -> {
                                modifierState.lockAndToggle(modifierType)
                            }
                            isRepetitive -> {
                                onDispatch(button.key)
                                repeatJob?.cancel()
                                repeatJob = scope.launch {
                                    while (isActive) {
                                        delay(REPEAT_DELAY_MS)
                                        onDispatch(button.key)
                                    }
                                }
                            }
                            button.popup != null -> {
                                showPopupMenu = true
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = button.key.display,
            color = textColor,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (button.popup != null) {
            DropdownMenu(
                expanded = showPopupMenu,
                onDismissRequest = { showPopupMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(button.popup.display) },
                    onClick = {
                        onDispatch(button.popup)
                        showPopupMenu = false
                    }
                )
            }
        }
    }
}

private fun ExtraKeysModifierState.isSpecialLocked(type: SpecialKeyType): Boolean = when (type) {
    SpecialKeyType.CTRL -> ctrl.isLocked
    SpecialKeyType.ALT -> alt.isLocked
    SpecialKeyType.SHIFT -> shift.isLocked
    SpecialKeyType.FN -> fn.isLocked
    else -> false
}
