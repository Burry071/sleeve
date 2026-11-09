@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.junkfood.seal.ui.page.downloadv2

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junkfood.seal.R
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction
import com.junkfood.seal.ui.common.AsyncImageImpl
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.LocalFixedColorRoles
import com.junkfood.seal.ui.common.motion.materialSharedAxisY
import com.junkfood.seal.ui.component.GreenTonalPalettes
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.toDurationText
import com.junkfood.seal.util.toFileSizeText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

private val IconButtonSize = 64.dp
private val IconSize = 36.dp
private val ActionButtonContainerColor: Color
    @Composable get() = LocalFixedColorRoles.current.onSecondaryFixed.copy(alpha = 0.68f)
private val ActionButtonContentColor: Color
    @Composable get() = LocalFixedColorRoles.current.secondaryFixed

/** Thumbnail scrim keeps badge text legible over bright artwork. */
private val ThumbnailScrim: Brush =
    Brush.verticalGradient(
        0f to Color.Transparent,
        0.72f to Color.Black.copy(alpha = 0.08f),
        1f to Color.Black.copy(alpha = 0.42f),
    )

@Composable
fun VideoCardV2(
    modifier: Modifier = Modifier,
    viewState: Task.ViewState,
    stateIndicator: @Composable (BoxScope.() -> Unit)? = null,
    actionButton: @Composable (BoxScope.() -> Unit)? = null,
    progress: Float? = null,
    footerContent: (@Composable () -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    with(viewState) {
        VideoCardV2(
            modifier = modifier,
            thumbnailModel = thumbnailUrl,
            title = title,
            uploader = uploader,
            duration = duration,
            fileSizeApprox = fileSizeApprox,
            stateIndicator = stateIndicator,
            actionButton = actionButton,
            progress = progress,
            footerContent = footerContent,
            onButtonClick = onButtonClick,
        )
    }
}

@Composable
fun VideoListItem(
    modifier: Modifier = Modifier,
    viewState: Task.ViewState,
    stateIndicator: @Composable (() -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    with(viewState) {
        VideoListItem(
            modifier = modifier,
            thumbnailModel = thumbnailUrl,
            title = title,
            uploader = uploader,
            duration = duration,
            fileSizeApprox = fileSizeApprox,
            stateIndicator = stateIndicator,
            onButtonClick = onButtonClick,
        )
    }
}

@Composable
fun VideoListItem(
    modifier: Modifier = Modifier,
    thumbnailModel: Any? = null,
    title: String = "",
    uploader: String = "",
    duration: Int = 0,
    fileSizeApprox: Double = .0,
    stateIndicator: @Composable (() -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    Row(modifier = modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier) {
            ListItemImage(modifier = Modifier, thumbnailModel = thumbnailModel)
            Box(
                modifier =
                    Modifier.matchParentSize().background(
                        brush =
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.36f),
                            )
                    )
            )
            VideoInfoLabel(
                modifier = Modifier.align(Alignment.BottomEnd),
                duration = duration,
                fileSizeApprox = fileSizeApprox,
            )
        }
        Box {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                TitleText(
                    modifier = Modifier,
                    title = title,
                    uploader = uploader,
                    contentPadding = PaddingValues(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                stateIndicator?.invoke()
            }
            IconButton(
                onButtonClick,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.show_more_actions),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Preview
@Composable
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES)
private fun VideoListItemPreview() {
    SealTheme {
        val fakeStateList =
            listOf(
                Running(Job(), "", 0.58f),
                Error(throwable = Throwable(), RestartableAction.Download),
                FetchingInfo(Job(), ""),
                Canceled(RestartableAction.Download),
                ReadyWithInfo,
                Idle,
                Completed(null),
            )

        var downloadState: Task.DownloadState by remember { mutableStateOf(Idle) }

        LaunchedEffect(Unit) {
            fakeStateList.forEach {
                delay(2000)
                downloadState = it
            }
        }

        Surface {
            VideoListItem(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 20.dp),
                thumbnailModel = R.drawable.sample3,
                title = stringResource(R.string.video_title_sample_text),
                uploader = stringResource(R.string.video_creator_sample_text),
                stateIndicator = {
                    ListItemStateText(
                        modifier = Modifier.padding(top = 3.dp),
                        downloadState = downloadState,
                    )
                },
            ) {}
        }
    }
}

@Composable
fun VideoCardV2(
    modifier: Modifier = Modifier,
    thumbnailModel: Any? = null,
    title: String = "",
    uploader: String = "",
    duration: Int = 0,
    fileSizeApprox: Double = .0,
    stateIndicator: @Composable (BoxScope.() -> Unit)? = null,
    actionButton: @Composable (BoxScope.() -> Unit)? = null,
    progress: Float? = null,
    footerContent: (@Composable () -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    val containerColor =
        MaterialTheme.colorScheme.run {
            if (LocalDarkTheme.current.isDarkTheme()) surfaceContainer else surfaceContainerLowest
        }
    val denseBottom = (progress != null && progress >= 0f) || footerContent != null

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column {
            Box(Modifier.fillMaxWidth()) {
                CardImage(modifier = Modifier, thumbnailModel = thumbnailModel)
                Box(Modifier.matchParentSize().background(brush = ThumbnailScrim))
                Box(Modifier.align(Alignment.TopStart)) { stateIndicator?.invoke(this) }
                Box(Modifier.align(Alignment.Center)) { actionButton?.invoke(this) }
                VideoInfoLabel(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    duration = duration,
                    fileSizeApprox = fileSizeApprox,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TitleText(
                    modifier = Modifier.weight(1f),
                    title = title,
                    uploader = uploader,
                    contentPadding =
                        if (denseBottom)
                            PaddingValues(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)
                        else PaddingValues(16.dp),
                )
                IconButton(
                    onButtonClick,
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.show_more_actions),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (progress != null && progress >= 0f) {
                val animatedProgress by
                    animateFloatAsState(
                        targetValue = progress,
                        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                        label = "cardProgress",
                    )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "%.1f%%".format(progress * 100f),
                        style =
                            MaterialTheme.typography.labelLarge.copy(
                                fontFeatureSettings = "tnum",
                                fontWeight = FontWeight.SemiBold,
                            ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (footerContent != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                ) {
                    footerContent()
                }
            }
        }
    }
}

@Composable
@Preview
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES)
fun VideoCardV2Preview() {
    SealTheme {
        val downloadState = Error(throwable = Throwable(), action = RestartableAction.Download)
        VideoCardV2(
            thumbnailModel = R.drawable.sample3,
            title = stringResource(R.string.video_title_sample_text),
            uploader = stringResource(R.string.video_creator_sample_text),
            actionButton = { ActionButton(modifier = Modifier, downloadState = downloadState) {} },
            stateIndicator = {
                CardStateIndicator(modifier = Modifier, downloadState = downloadState)
            },
        ) {}
    }
}

@Composable
private fun CardImage(modifier: Modifier = Modifier, thumbnailModel: Any? = null) {
    if (thumbnailModel != null) {
        AsyncImageImpl(
            modifier =
                modifier
                    .padding()
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
            model = thumbnailModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier =
                modifier
                    .padding()
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {}
    }
}

@Composable
private fun ListItemImage(modifier: Modifier = Modifier, thumbnailModel: Any? = null) {
    if (thumbnailModel != null) {
        AsyncImageImpl(
            model = thumbnailModel,
            modifier =
                Modifier.width(160.dp)
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
            contentDescription = null,
        )
    } else {
        Box(
            modifier =
                modifier
                    .width(160.dp)
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {}
    }
}

@Composable
private fun TitleText(
    modifier: Modifier = Modifier,
    title: String,
    uploader: String,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    Column(
        modifier = modifier.padding(contentPadding),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            modifier = Modifier.padding(top = 3.dp),
            text = uploader,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VideoInfoLabel(modifier: Modifier = Modifier, duration: Int, fileSizeApprox: Double) {
    // Direct/CDN lanes know neither size nor duration until the file lands.
    // "0.00 MB · 00:00" would be an invented value, so unknown parts are
    // dropped and a fully unknown label is not drawn at all.
    val parts =
        buildList {
            if (fileSizeApprox > 0) add(fileSizeApprox.toFileSizeText())
            if (duration > 0) add(duration.toDurationText())
        }
    if (parts.isEmpty()) return
    Surface(
        modifier = modifier.padding(10.dp),
        color = Color.Black.copy(alpha = 0.62f),
        shape = CircleShape,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/** Tonal pair for a state pill: [container, content]. */
@Composable
private fun statePillColors(downloadState: Task.DownloadState): Pair<Color, Color> {
    val isDarkTheme = LocalDarkTheme.current.isDarkTheme()
    return when (downloadState) {
        is Error ->
            MaterialTheme.colorScheme.run { errorContainer to onErrorContainer }
        is Completed -> {
            val container = GreenTonalPalettes.accent1(if (isDarkTheme) 30.0 else 90.0)
            val content = GreenTonalPalettes.accent1(if (isDarkTheme) 90.0 else 30.0)
            container to content
        }
        is Running, is FetchingInfo ->
            MaterialTheme.colorScheme.run { primaryContainer to onPrimaryContainer }
        else ->
            MaterialTheme.colorScheme.run { surfaceContainerHighest to onSurfaceVariant }
    }
}

@Composable
fun CardStateIndicator(modifier: Modifier = Modifier, downloadState: Task.DownloadState) {
    AnimatedContent(
        targetState = downloadState,
        transitionSpec = {
            materialSharedAxisY(initialOffsetY = { it / 4 }, targetOffsetY = { -it / 4 })
        },
        contentKey = { it::class.simpleName },
        label = "cardStatePill",
        modifier = modifier,
    ) { state ->
        val (container, content) = statePillColors(state)
        Surface(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
            color = container,
            shape = CircleShape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is Error -> {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            tint = content,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    is Completed -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = content,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    else -> Unit
                }
                Text(
                    text = statePillText(state),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                            letterSpacing = 0.2.sp,
                        ),
                    color = content,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun statePillText(downloadState: Task.DownloadState): String =
    when (downloadState) {
        is Canceled -> stringResource(R.string.status_canceled)
        is Completed -> stringResource(R.string.status_downloaded)
        is Error -> stringResource(R.string.status_error)
        is FetchingInfo -> stringResource(R.string.status_fetching_video_info)
        Idle, ReadyWithInfo -> stringResource(R.string.status_enqueued)
        is Running -> {
            val progress = downloadState.progress
            if (progress >= 0) "%.1f%%".format(progress * 100f)
            else stringResource(R.string.status_downloading)
        }
    }

@Composable
fun ListItemStateText(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = LocalDarkTheme.current.isDarkTheme(),
    downloadState: Task.DownloadState,
) {
    val sizeModifier = Modifier.size(14.dp)

    AnimatedContent(
        downloadState,
        transitionSpec = {
            materialSharedAxisY(initialOffsetY = { it / 5 }, targetOffsetY = { -it / 5 })
        },
        contentKey = { it::class.simpleName },
    ) { downloadState ->
        val text =
            when (downloadState) {
                is Canceled -> stringResource(R.string.status_canceled)
                is Completed -> stringResource(R.string.status_downloaded)
                is Error -> stringResource(R.string.status_error)
                is FetchingInfo -> stringResource(R.string.status_fetching_video_info)
                Idle -> stringResource(R.string.status_enqueued)
                ReadyWithInfo -> stringResource(R.string.status_enqueued)
                is Running -> {
                    val progress = downloadState.progress
                    if (progress >= 0) {
                        "%.1f %%".format(downloadState.progress * 100)
                    } else {
                        stringResource(R.string.status_downloading)
                    }
                }
            }
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            when (downloadState) {
                is Canceled -> {
                    Icon(
                        imageVector = Icons.Outlined.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = sizeModifier,
                    )
                }
                is Completed -> {
                    val color = GreenTonalPalettes.accent1(if (isDarkTheme) 80.0 else 40.0)
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = color,
                        modifier = sizeModifier,
                    )
                }
                is Error -> {
                    Icon(
                        imageVector = Icons.Rounded.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = sizeModifier,
                    )
                }
                is FetchingInfo,
                Idle,
                ReadyWithInfo -> {
                    CircularProgressIndicator(modifier = sizeModifier, strokeWidth = 2.5.dp)
                }
                is Running -> {
                    val progress = downloadState.progress
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = sizeModifier,
                        strokeWidth = 2.5.dp,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = text,
                modifier = Modifier,
                style =
                    MaterialTheme.typography.labelMedium.merge(letterSpacing = 0.sp).let {
                        it.copy(fontFeatureSettings = "tnum")
                    },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Inline retry action rendered on failed task cards. A login-walled failure
 * also offers the cookie route, so recovery does not require re-sharing the
 * link.
 */
@Composable
fun ErrorFooter(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onRetryWithCookies: (() -> Unit)? = null,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        FilledTonalButton(
            onClick = onRetry,
            modifier = Modifier,
            colors =
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Icon(
                imageVector = Icons.Rounded.RestartAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = stringResource(R.string.retry))
        }
        onRetryWithCookies?.let {
            TextButton(onClick = it, modifier = Modifier) {
                Text(text = stringResource(R.string.retry_with_cookies))
            }
        }
    }
}

@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    downloadState: Task.DownloadState,
    onActionPost: (UiAction) -> Unit,
) =
    when (downloadState) {
        is Error -> {
            RestartButton(modifier = modifier) { onActionPost(UiAction.Resume) }
        }
        is Canceled -> {
            ResumeButton(modifier = modifier, downloadState.progress) {
                onActionPost(UiAction.Resume)
            }
        }
        is Completed -> {
            PlayVideoButton(modifier = modifier) {
                onActionPost(UiAction.OpenFile(downloadState.filePath))
            }
        }
        is FetchingInfo,
        ReadyWithInfo,
        Idle -> {
            ProgressButton(modifier = modifier, progress = -1f) { onActionPost(UiAction.Cancel) }
        }
        is Running -> {
            ProgressButton(modifier = modifier, progress = downloadState.progress) {
                onActionPost(UiAction.Cancel)
            }
        }
    }

@Composable
private fun ResumeButton(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    val background = ActionButtonContainerColor

    Box(
        modifier =
            modifier
                .size(IconButtonSize)
                .clip(CircleShape)
                .drawBehind { drawCircle(background) }
                .clickable(onClickLabel = stringResource(R.string.cancel), onClick = onClick)
    ) {
        if (progress != null) {
            CircularWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(IconButtonSize).align(Alignment.Center),
                color = ActionButtonContentColor,
                trackColor = Color.Transparent,
            )
        }
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = stringResource(R.string.restart),
            modifier = Modifier.size(IconSize).align(Alignment.Center),
            tint = ActionButtonContentColor,
        )
    }
}

@Composable
fun RestartButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background = ActionButtonContainerColor

    Box(
        modifier =
            modifier
                .size(IconButtonSize)
                .clip(CircleShape)
                .drawBehind { drawCircle(background) }
                .clickable(onClickLabel = stringResource(R.string.cancel), onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.Rounded.RestartAlt,
            contentDescription = stringResource(R.string.restart),
            modifier = Modifier.size(IconSize).align(Alignment.Center),
            tint = ActionButtonContentColor,
        )
    }
}

@Composable
private fun PlayVideoButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(IconButtonSize),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = ActionButtonContainerColor,
                contentColor = ActionButtonContentColor,
            ),
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.open_file),
            modifier = Modifier.size(IconSize),
        )
    }
}

@Composable
private fun ProgressButton(modifier: Modifier = Modifier, progress: Float, onClick: () -> Unit) {
    val animatedProgress by
        animateFloatAsState(
            progress,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "progress",
        )
    val background = ActionButtonContainerColor

    Box(
        modifier =
            modifier
                .size(IconButtonSize)
                .clip(CircleShape)
                .drawBehind { drawCircle(background) }
                .clickable(onClickLabel = stringResource(R.string.cancel), onClick = onClick)
    ) {
        if (progress < 0) {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(IconButtonSize).align(Alignment.Center),
                color = ActionButtonContentColor,
                trackColor = Color.Transparent,
            )
        } else {
            CircularWavyProgressIndicator(
                { animatedProgress },
                modifier = Modifier.size(IconButtonSize).align(Alignment.Center),
                color = ActionButtonContentColor,
                trackColor = Color.Transparent,
            )
        }
        Icon(
            imageVector = Icons.Rounded.Pause,
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(IconSize),
            tint = ActionButtonContentColor,
        )
    }
}
