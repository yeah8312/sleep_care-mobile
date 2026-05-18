package com.sleepcare.watch.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.sleepcare.watch.model.WatchUiState

// Wear OS 원형/소형 화면에 맞춘 워치 전용 Compose 화면 모음입니다.
private val ScreenHorizontalPadding = 14.dp
private val ScreenVerticalPadding = 28.dp
private val QuickActionSize = 36.dp
private val OrbSize = 64.dp

// 휴대폰에서 세션 시작 명령이 오기 전 대기 화면입니다.
@Composable
fun ConnectionWaitingScreen(
    state: WatchUiState,
    onOpenSettings: () -> Unit,
    onStartDemoSession: () -> Unit,
    onRetryConnection: () -> Unit,
) {
    ScreenContainer {
        StatusOrb(
            icon = Icons.Filled.WifiOff,
            iconTint = MaterialTheme.colors.primary,
            title = state.connectionTitle,
            subtitle = state.connectionSubtitle,
            badge = state.connectionBadge,
        )
        Spacer(Modifier.height(8.dp))
        Chip(
            onClick = onStartDemoSession,
            label = { Text("Open Session") },
            colors = ChipDefaults.primaryChipColors(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(Icons.Filled.Refresh, "Retry", onRetryConnection)
            IconAction(Icons.Filled.Settings, "Settings", onOpenSettings)
        }
    }
}

// 심박 수집 중인 세션 화면입니다. BPM, IBI, 최근 동기화 상태를 크게 보여줍니다.
@Composable
fun ActiveSessionScreen(
    state: WatchUiState,
    onOpenSettings: () -> Unit,
    onTriggerAlert: () -> Unit,
    onStopSession: () -> Unit,
) {
    // 실제 SDK 세션에서는 첫 DataPoint가 도착하기 전까지 측정값과 대기 상태를 구분해 보여줍니다.
    val heartRateLabel = state.latestHeartRate.takeIf { it > 0 }?.toString() ?: "--"
    val ibiLabel = state.latestIbiMs
        .takeIf { state.latestSample != null && it > 0 }
        ?.let { "IBI: ${it}ms" }
        ?: "IBI: waiting"

    ScreenContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(text = "SENSOR", icon = Icons.Filled.Bluetooth, active = true)
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Filled.Settings, "Settings", onOpenSettings)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Color(0xFF111415))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = heartRateLabel,
                    style = MaterialTheme.typography.display1,
                    color = MaterialTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 44.sp,
                    textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFFB7C0FF),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "BPM",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.caption3,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Chip(
                    onClick = onTriggerAlert,
                    label = { Text(ibiLabel) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.lastSyncLabel,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.caption3,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(Icons.Filled.NotificationsActive, "Alert", onTriggerAlert)
            IconAction(Icons.Filled.DirectionsRun, "Stop", onStopSession)
        }
    }
}

// Pi가 위험 상태를 감지해 워치 진동 알림을 띄울 때 사용하는 화면입니다.
@Composable
fun AlertingScreen(
    state: WatchUiState,
    onDismiss: () -> Unit,
) {
    ScreenContainer {
        StatusOrb(
            icon = Icons.Filled.NotificationsActive,
            iconTint = Color(0xFFBDC2FF),
            title = state.alertTitle,
            subtitle = state.alertBody,
            badge = state.alertBadge,
            badgeColor = Color(0xFF44D8F1),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onDismiss,
        ) {
            Text(state.alertActionLabel)
        }
    }
}

// 권한, 수면 로그, 센서 상태를 작은 칩 목록으로 보여주는 설정 화면입니다.
@Composable
fun WatchSettingsScreen(
    state: WatchUiState,
    onBackToConnection: () -> Unit,
    onBackToSession: () -> Unit,
    onRefreshSleepSync: () -> Unit,
) {
    ScreenContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            SettingsRow(
                title = "Permission Status",
                subtitle = state.permissionsStatusLabel,
                icon = Icons.Filled.CheckCircle,
            )
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                title = "Sleep Log",
                subtitle = state.sleepSyncStatus,
                icon = Icons.Filled.Refresh,
                onClick = onRefreshSleepSync,
            )
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                title = "Sensor Info",
                subtitle = state.trackingStatus,
                icon = Icons.Filled.SignalCellularAlt,
                onClick = onBackToSession,
            )
            Spacer(Modifier.height(8.dp))
            MessageLogBox(logItems = state.messageLog)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconAction(Icons.Filled.WifiOff, "Connection", onBackToConnection)
            IconAction(Icons.Filled.DirectionsRun, "Session", onBackToSession)
            IconAction(Icons.Filled.Settings, "Settings", onBackToConnection)
        }
    }
}

// 폰에서 보낸 메시지가 워치 listener까지 도착했는지 현장에서 확인하기 위한 최근 수신 로그입니다.
@Composable
private fun MessageLogBox(logItems: List<String>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1C1D))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "Message Log",
                color = MaterialTheme.colors.onSurface,
                style = MaterialTheme.typography.caption3,
                fontWeight = FontWeight.Bold,
            )
            if (logItems.isEmpty()) {
                Text(
                    text = "No messages yet",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.caption3,
                )
            } else {
                logItems.forEach { item ->
                    Text(
                        text = item,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.caption3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// TimeText가 차지하는 상단과 원형 화면 하단을 피해 배치하고, 작은 기기에서는 세로 스크롤로 잘림을 방지합니다.
@Composable
private fun ScreenContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenHorizontalPadding, vertical = ScreenVerticalPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// 브랜드/상태 라벨을 짧게 보여주는 상단 줄입니다.
@Composable
private fun BrandLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colors.primary,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "NOCTURNE SCIENCE",
            color = MaterialTheme.colors.onSurface,
            style = MaterialTheme.typography.caption3,
            fontWeight = FontWeight.Bold,
        )
    }
}

// 아이콘을 둥근 원 안에 배치해 대기/알림 화면의 중심 요소로 사용합니다.
@Composable
private fun StatusOrb(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color = MaterialTheme.colors.secondary,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 중첩 원형 배경을 하나로 줄여 화면 전환 때 측정/그리기 비용을 낮춥니다.
        Box(
            modifier = Modifier
                .size(OrbSize)
                .clip(CircleShape)
                .background(Color(0xFF1A1C1D))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            color = MaterialTheme.colors.onSurface,
            style = MaterialTheme.typography.title3,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            modifier = Modifier.fillMaxWidth(0.82f),
            text = subtitle,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.82f),
            style = MaterialTheme.typography.caption3,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        StatusBadge(text = badge, color = badgeColor)
    }
}

// 작은 원형 화면에서 badge용 Chip은 높이를 많이 쓰므로, 읽기 전용 상태는 얇은 pill로 표시합니다.
@Composable
private fun StatusBadge(
    text: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1C1D))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.caption3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// 설정 화면에서 쓰는 두 줄짜리 클릭 칩입니다.
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit = {},
) {
    Chip(
        onClick = onClick,
        label = { Text(title) },
        secondaryLabel = { Text(subtitle) },
        icon = {
            Icon(imageVector = icon, contentDescription = null)
        },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

// 세션 화면의 작은 상태 배지입니다.
@Composable
private fun StatusPill(
    text: String,
    icon: ImageVector,
    active: Boolean,
) {
    Chip(
        onClick = { },
        label = { Text(text) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        colors = if (active) {
            ChipDefaults.primaryChipColors()
        } else {
            ChipDefaults.secondaryChipColors()
        },
    )
}

// 텍스트와 아이콘이 함께 들어가는 작은 행동 칩입니다.
@Composable
private fun SmallActionChip(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        label = { Text(text) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

// 공간이 좁은 워치 화면에서는 공백 텍스트 칩 대신 실제 아이콘 버튼을 사용해 레이아웃 계산을 줄입니다.
@Composable
private fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(QuickActionSize),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
