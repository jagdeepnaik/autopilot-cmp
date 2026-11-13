package com.jagdeep.autopilot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Caption actions share the app toolbar instead of adding a second Windows title row. */
@Composable
internal fun WindowsWindowControls(
    maximized: Boolean,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    onClose: () -> Unit,
    maximizeModifier: Modifier = Modifier,
) {
    Row {
        IconButton(onMinimize, Modifier.width(46.dp).height(38.dp)) {
            Icon(Icons.Default.Remove, "Minimize window", Modifier.size(16.dp))
        }
        IconButton(onMaximize, maximizeModifier.width(46.dp).height(38.dp)) {
            Icon(if (maximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                if (maximized) "Restore window" else "Maximize window", Modifier.size(16.dp))
        }
        IconButton(onClose, Modifier.width(46.dp).height(38.dp)) {
            Icon(Icons.Default.Close, "Close window", Modifier.size(16.dp))
        }
    }
}

/** Small Windows caption identity, preceding all app toolbar actions. */
@Suppress("DEPRECATION")
@Composable
internal fun WindowsWindowIdentity(onHome: () -> Unit = {}) {
    Row(
        Modifier.padding(start = 8.dp, end = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource("autopilot-icon.png"),
            contentDescription = "AutoPilot window icon",
            modifier = Modifier.size(24.dp).then(Modifier.clickable(onClickLabel = "Open Home", onClick = onHome)),
        )
        androidx.compose.material3.Text(
            com.jagdeep.common.config.AppConfig.APP_NAME,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
