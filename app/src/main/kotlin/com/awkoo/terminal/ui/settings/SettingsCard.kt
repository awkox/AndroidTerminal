package com.awkoo.terminal.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设置分组卡片。
 *
 * 把一组 preference tile / 输入行包进圆角容器，配合 [SettingsScreen] 的页面留边，
 * 让分组边界与屏幕边缘拉开距离；Surface 按圆角裁剪子项，tile 的按压水波
 * 不会溢出卡片圆角。8dp 纵向内边距为组内的输入框留出呼吸空间。
 */
@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            content = content
        )
    }
}

/**
 * 卡片内 preference tile 的配色。
 *
 * compose-settings 的 tile 默认容器色为 `colorScheme.surface`（即页面底色），
 * 落在 surfaceContainer 卡片上会呈现页面底色的方块；改为透明以露出卡片底色。
 */
@Composable
fun CardTileColors(): ListItemColors = ListItemDefaults.colors(
    containerColor = Color.Transparent
)
