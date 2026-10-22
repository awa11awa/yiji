package cn.yiji.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun StatusFilter(selected: Boolean, label: String, unread: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label)
            if (unread) Badge(containerColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "$label 有未读更新" })
        }
    })
}
