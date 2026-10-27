package com.junkfood.seal.ui.page.settings.format

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.RANKING_STRICTNESS

private val MATCH_LEVELS =
    listOf(
        0 to R.string.ranking_loose,
        1 to R.string.ranking_normal,
        2 to R.string.ranking_strict,
    )

@Composable
fun MatchQualityDialog(onDismissRequest: () -> Unit = {}) {
    val current = RANKING_STRICTNESS.getInt()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.Tune, null) },
        title = { Text(text = stringResource(id = R.string.match_quality)) },
        text = {
            Column {
                MATCH_LEVELS.forEach { (level, labelRes) ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable {
                                    RANKING_STRICTNESS.updateInt(level)
                                    onDismissRequest()
                                }
                                .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == level, onClick = null)
                        Text(
                            text = stringResource(id = labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { ConfirmButton { onDismissRequest() } },
    )
}
