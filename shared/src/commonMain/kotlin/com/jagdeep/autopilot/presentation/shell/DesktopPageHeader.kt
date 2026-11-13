package com.jagdeep.autopilot.presentation.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** Reuse the page's complete Material header in the desktop window toolbar, without a second bar. */
@OptIn(ExperimentalMaterial3ComponentOverrideApi::class)
@Composable
internal fun DesktopPageHeader(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSingleRowTopAppBarOverride provides CompactPageHeader,
        LocalMinimumInteractiveComponentSize provides 24.dp,
    ) { content() }
}

@OptIn(ExperimentalMaterial3ComponentOverrideApi::class, ExperimentalMaterial3Api::class)
private object CompactPageHeader : SingleRowTopAppBarOverride {
    @Composable
    override fun SingleRowTopAppBarOverrideScope.SingleRowTopAppBar() {
        Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { navigationIcon() }
            Box(Modifier.semantics { heading() }) {
                ProvideTextStyle(MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)) { title() }
            }
            subtitle?.let { ProvideTextStyle(MaterialTheme.typography.labelSmall) { it() } }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
