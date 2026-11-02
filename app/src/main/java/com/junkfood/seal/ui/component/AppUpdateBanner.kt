package com.junkfood.seal.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.pressScale
import com.junkfood.seal.util.AppUpdateManager
import com.junkfood.seal.util.AppUpdateManager.State
import kotlinx.coroutines.launch

/**
 * In-app update banner: shows at the top of the download queue when a newer
 * release exists on GitHub, downloads the APK with visible progress, and
 * hands the finished file to the system installer. States cross-fade with a
 * short upward slide; exits run faster than entrances (M3 expressive motion).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val state by AppUpdateManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val visible =
        when (val s = state) {
            is State.Available, is State.Downloading, is State.Ready, is State.Failed -> true
            else -> false
        }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(240)) + fadeIn(tween(240)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            AnimatedContent(
                targetState = state,
                // contentKey on the state class: progress ticks must not re-run
                // the whole transition, only real state changes should.
                contentKey = { it::class },
                transitionSpec = {
                    (
                        fadeIn(tween(220)) +
                            slideInVertically(tween(220)) { it / 4 }
                        ) togetherWith (
                        fadeOut(tween(140)) +
                            slideOutVertically(tween(140)) { -it / 6 }
                        )
                },
                label = "updateBanner",
            ) { s ->
                when (s) {
                    is State.Available -> {
                        val interaction = remember { MutableInteractionSource() }
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdateAlt,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.app_update_banner_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text =
                                        stringResource(
                                            R.string.app_update_banner_subtitle,
                                            s.version,
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        AppUpdateManager.download(context, s.version, s.apkUrl)
                                    }
                                },
                                interactionSource = interaction,
                                modifier = Modifier.pressScale(interaction),
                            ) {
                                Text(stringResource(R.string.update))
                            }
                        }
                    }

                    is State.Downloading -> {
                        val interaction = remember { MutableInteractionSource() }
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LinearWavyProgressIndicator(
                                progress = { s.progress / 100f },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "${s.progress}%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = { AppUpdateManager.cancelDownload() },
                                interactionSource = interaction,
                                modifier = Modifier.pressScale(interaction),
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }

                    is State.Ready -> {
                        val interaction = remember { MutableInteractionSource() }
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdateAlt,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.app_update_banner_downloaded, s.version),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.app_update_banner_tap_install),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            FilledTonalButton(
                                onClick = { AppUpdateManager.install(context, s.file) },
                                interactionSource = interaction,
                                modifier = Modifier.pressScale(interaction),
                            ) {
                                Text(stringResource(R.string.install))
                            }
                        }
                    }

                    is State.Failed -> {
                        val interaction = remember { MutableInteractionSource() }
                        Column(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.app_update_banner_failed, s.message),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            FilledTonalButton(
                                onClick = { scope.launch { AppUpdateManager.check(context) } },
                                interactionSource = interaction,
                                modifier = Modifier.pressScale(interaction),
                            ) {
                                Text(stringResource(R.string.app_update_banner_retry))
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}
