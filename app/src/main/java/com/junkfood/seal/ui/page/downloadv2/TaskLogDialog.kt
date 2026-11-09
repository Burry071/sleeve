package com.junkfood.seal.ui.page.downloadv2

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskAuditLog

/** Runtime audit trail for one task: its cookie state and every match score. */
@Composable
fun TaskLogDialog(
    task: Task,
    onDismissRequest: () -> Unit,
) {
    val lines = TaskAuditLog.read(task.id)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.task_log_title)) },
        text = {
            if (lines.isEmpty()) {
                Text(stringResource(R.string.no_logs_yet))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.close)) }
        },
    )
}
