package com.lazydevs.wristotle.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Models tab — thin wrapper around [WhisperModelsCard] so the screen has
 * its own padding/scroll envelope independent of the Watch and Voice tabs.
 */
@Composable
fun ModelsScreen(vm: WhisperModelsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        WhisperModelsCard(vm = vm)
    }
}
