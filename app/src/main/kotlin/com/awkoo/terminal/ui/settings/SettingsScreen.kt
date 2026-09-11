package com.awkoo.terminal.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 设置页通用模板。
 *
 * 顶部为带返回按钮的 [TopAppBar]，内容区为一个空的 [LazyColumn]。
 * 后续 preference tile（compose-settings）直接放入 [content] 即可，
 * 嵌套页面复用本模板，仅需更换 [title] 与 [content]。
 *
 * @param title 页面标题
 * @param showBackButton 是否显示返回按钮。根页面如需在初始状态隐藏返回，可传 false
 * @param onBack 返回按钮回调
 * @param content 页面主体内容，以 [LazyListScope] 为接收者，可直接使用 [LazyListScope.item]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    title: String,
    showBackButton: Boolean,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            content = content
        )
    }
}
