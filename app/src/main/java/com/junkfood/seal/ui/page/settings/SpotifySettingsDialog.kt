package com.junkfood.seal.ui.page.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.SPOTIFY_CLIENT_ID
import com.junkfood.seal.util.SPOTIFY_CLIENT_SECRET

/**
 * Credentials for the Spotify metadata resolver (spotDL pattern — Spotify is
 * used for metadata only; audio is fetched from YouTube via yt-dlp). Free
 * client credentials: developer.spotify.com dashboard.
 */
@Composable
fun SpotifyCredentialsDialog(onDismissRequest: () -> Unit = {}) {
    var clientId by remember { mutableStateOf(SPOTIFY_CLIENT_ID.getString()) }
    var clientSecret by remember { mutableStateOf(SPOTIFY_CLIENT_SECRET.getString()) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Rounded.QueueMusic, null) },
        title = { Text(text = "Spotify") },
        text = {
            Column {
                Text(
                    text =
                        "Spotify playlists are resolved for metadata and matched on " +
                            "YouTube (spotDL pattern). Paste your own free client " +
                            "credentials from developer.spotify.com — the shared " +
                            "defaults are heavily rate-limited.",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    value = clientId,
                    label = { Text(text = "Client ID") },
                    onValueChange = { clientId = it },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                    value = clientSecret,
                    label = { Text(text = "Client secret") },
                    onValueChange = { clientSecret = it },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        dismissButton = { ConfirmButton(text = "Cancel") { onDismissRequest() } },
        confirmButton = {
            ConfirmButton {
                SPOTIFY_CLIENT_ID.updateString(clientId.trim())
                SPOTIFY_CLIENT_SECRET.updateString(clientSecret.trim())
                onDismissRequest()
            }
        },
    )
}
