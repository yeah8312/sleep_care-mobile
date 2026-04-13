package com.sleepcare.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepcare.mobile.ui.theme.SleepCareOnSurface
import com.sleepcare.mobile.ui.theme.SleepCareOnSurfaceVariant
import com.sleepcare.mobile.ui.theme.SleepCareErrorContainer
import com.sleepcare.mobile.ui.theme.SleepCareOutlineVariant
import com.sleepcare.mobile.ui.theme.SleepCarePrimary
import com.sleepcare.mobile.ui.theme.SleepCarePrimaryContainer
import com.sleepcare.mobile.ui.theme.SleepCareSurface
import com.sleepcare.mobile.ui.theme.SleepCareSurfaceContainer
import com.sleepcare.mobile.ui.theme.SleepCareSurfaceContainerHigh
import com.sleepcare.mobile.ui.theme.SleepCareSurfaceContainerHighest
import com.sleepcare.mobile.ui.theme.SleepCareSurfaceContainerLow
import com.sleepcare.mobile.ui.theme.SleepCareSurfaceContainerLowest
import com.sleepcare.mobile.ui.theme.SleepCareSurfaceTint
import com.sleepcare.mobile.ui.theme.SleepCareTertiary
import com.sleepcare.mobile.ui.theme.SleepCareTertiaryContainer

data class TimelineSegment(
    val label: String,
    val percentage: Float,
    val color: Color,
    val description: String? = null
)

enum class DeviceVisualStatus {
    Connected,
    Connecting,
    Disconnected,
    Error
}

data class BottomBarItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val contentDescription: String
)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tone: Color = SleepCareSurfaceContainerHigh,
    borderColor: Color = SleepCareOutlineVariant.copy(alpha = 0.45f),
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        color = tone.copy(alpha = 0.72f),
        contentColor = SleepCareOnSurface,
        shape = shape,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            tone.copy(alpha = 0.92f),
                            tone.copy(alpha = 0.78f)
                        )
                    )
                )
                .border(width = 1.dp, color = borderColor, shape = shape)
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

@Composable
fun MetricHeroCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accent: Color = SleepCarePrimary,
    trailingLabel: String? = null,
    supportingText: String? = null,
    onClick: (() -> Unit)? = null
) {
    val containerModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    GlassCard(
        modifier = containerModifier.fillMaxWidth(),
        tone = SleepCareSurfaceContainerLow,
        borderColor = accent.copy(alpha = 0.22f),
        contentPadding = PaddingValues(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = SleepCareOnSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.displaySmall,
                        color = accent
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepCareOnSurfaceVariant
                    )
                }
                trailingLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = SleepCareOnSurfaceVariant
                    )
                }
            }
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepCareOnSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun InsightCallout(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    accent: Color = SleepCareTertiary,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Insights,
    tone: Color = SleepCareSurfaceContainerLowest,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        tone = tone,
        borderColor = accent.copy(alpha = 0.18f),
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SleepCareOnSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepCareOnSurfaceVariant
                )
                if (actionLabel != null && onActionClick != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        modifier = Modifier.clickable(onClick = onActionClick)
                    )
                }
            }
        }
    }
}

@Composable
fun TimelineBar(
    segments: List<TimelineSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    labels: List<String> = emptyList(),
    showLegend: Boolean = true
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(999.dp))
                .background(SleepCareSurfaceContainer)
                .border(1.dp, SleepCareOutlineVariant.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
        ) {
            segments.forEach { segment ->
                Box(
                    modifier = Modifier
                        .weight(segment.percentage.coerceIn(0.01f, 1f))
                        .height(height)
                        .background(segment.color)
                )
            }
        }
        if (labels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = SleepCareOnSurfaceVariant
                    )
                }
            }
        }
        if (showLegend) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.take(4).forEach { segment ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(segment.color)
                        )
                        Text(
                            text = segment.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = SleepCareOnSurfaceVariant
                        )
                        segment.description?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = SleepCareOnSurfaceVariant.copy(alpha = 0.72f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleHero(
    bedtime: String,
    wakeTime: String,
    totalSleep: String,
    reason: String,
    modifier: Modifier = Modifier,
    bedtimeLabel: String = "권장 취침",
    wakeTimeLabel: String = "권장 기상",
    primaryActionLabel: String? = null,
    secondaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        tone = SleepCareSurfaceContainerLow,
        borderColor = SleepCarePrimaryContainer.copy(alpha = 0.24f),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "추천 수면 스케줄",
                        style = MaterialTheme.typography.labelMedium,
                        color = SleepCareOnSurfaceVariant
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.titleLarge,
                        color = SleepCareOnSurface
                    )
                }
                Icon(
                    imageVector = Icons.Filled.WbTwilight,
                    contentDescription = null,
                    tint = SleepCareTertiary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ScheduleTimeTile(
                    modifier = Modifier.weight(1f),
                    label = bedtimeLabel,
                    value = bedtime,
                    accent = SleepCarePrimary
                )
                ScheduleTimeTile(
                    modifier = Modifier.weight(1f),
                    label = wakeTimeLabel,
                    value = wakeTime,
                    accent = SleepCareTertiary
                )
            }
            Text(
                text = totalSleep,
                style = MaterialTheme.typography.titleMedium,
                color = SleepCareOnSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    ActionChip(
                        text = primaryActionLabel,
                        accent = SleepCarePrimary,
                        onClick = onPrimaryAction
                    )
                }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    ActionChip(
                        text = secondaryActionLabel,
                        accent = SleepCareTertiary,
                        onClick = onSecondaryAction
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleTimeTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        tone = SleepCareSurfaceContainerHighest,
        borderColor = accent.copy(alpha = 0.2f),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = SleepCareOnSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = accent
            )
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    accent: Color,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.clickable(onClick = onClick),
        tone = accent.copy(alpha = 0.12f),
        borderColor = accent.copy(alpha = 0.3f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = SleepCareOnSurface
        )
    }
}

@Composable
fun DeviceStatusCard(
    deviceName: String,
    status: DeviceVisualStatus,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    connectionDetails: String? = null,
    statusLabel: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val accent = when (status) {
        DeviceVisualStatus.Connected -> SleepCareTertiary
        DeviceVisualStatus.Connecting -> SleepCarePrimary
        DeviceVisualStatus.Disconnected -> SleepCareOnSurfaceVariant
        DeviceVisualStatus.Error -> SleepCareErrorContainer
    }
    val animatedTint by animateColorAsState(
        targetValue = accent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "deviceStatusTint"
    )

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        tone = SleepCareSurfaceContainerLow,
        borderColor = animatedTint.copy(alpha = 0.22f),
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(animatedTint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (status) {
                        DeviceVisualStatus.Connected -> Icons.Filled.CheckCircle
                        DeviceVisualStatus.Connecting -> Icons.Filled.MoreHoriz
                        DeviceVisualStatus.Disconnected -> Icons.Filled.Devices
                        DeviceVisualStatus.Error -> Icons.Filled.ErrorOutline
                    },
                    contentDescription = null,
                    tint = animatedTint
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SleepCareOnSurface
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepCareOnSurfaceVariant
                    )
                }
                Text(
                    text = statusLabel ?: status.toDisplayName(),
                    style = MaterialTheme.typography.labelLarge,
                    color = animatedTint
                )
                connectionDetails?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = SleepCareOnSurfaceVariant
                    )
                }
                if (actionLabel != null && onActionClick != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = SleepCarePrimary,
                        modifier = Modifier.clickable(onClick = onActionClick)
                    )
                }
            }
        }
    }
}

@Composable
fun AppBottomBar(
    currentRoute: String,
    onRouteSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val destinations = listOf(
        BottomBarItem(
            route = "home",
            label = "홈",
            icon = Icons.Filled.Home,
            contentDescription = "홈으로 이동"
        ),
        BottomBarItem(
            route = "analysis",
            label = "분석",
            icon = Icons.Filled.Insights,
            contentDescription = "분석으로 이동"
        ),
        BottomBarItem(
            route = "schedule",
            label = "일정",
            icon = Icons.Filled.CalendarMonth,
            contentDescription = "일정으로 이동"
        ),
        BottomBarItem(
            route = "settings",
            label = "설정",
            icon = Icons.Filled.Settings,
            contentDescription = "설정으로 이동"
        )
    )

    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(SleepCareSurface.copy(alpha = 0.92f)),
        containerColor = SleepCareSurface.copy(alpha = 0.92f),
        tonalElevation = 0.dp
    ) {
        destinations.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = { onRouteSelected(item.route) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription
                    )
                },
                label = { Text(text = item.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SleepCarePrimary,
                    selectedTextColor = SleepCarePrimary,
                    unselectedIconColor = SleepCareOnSurfaceVariant,
                    unselectedTextColor = SleepCareOnSurfaceVariant,
                    indicatorColor = SleepCarePrimaryContainer.copy(alpha = 0.18f)
                )
            )
        }
    }
}

private fun DeviceVisualStatus.toDisplayName(): String = when (this) {
    DeviceVisualStatus.Connected -> "연결됨"
    DeviceVisualStatus.Connecting -> "스캔 중"
    DeviceVisualStatus.Disconnected -> "미연결"
    DeviceVisualStatus.Error -> "실패"
}
