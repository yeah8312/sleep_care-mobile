package com.sleepcare.mobile.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.ui.components.GlassCard
import com.sleepcare.mobile.ui.theme.SleepCarePrimary
import com.sleepcare.mobile.ui.theme.SleepCarePrimaryContainer
import com.sleepcare.mobile.ui.theme.SleepCareTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    paddingValues: PaddingValues,
    onStartClick: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                )
            )
            .padding(paddingValues)
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(SleepCarePrimaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Bedtime,
                contentDescription = null,
                tint = SleepCarePrimary,
            )
        }
        Text(
            text = "Sleep Care",
            style = MaterialTheme.typography.labelLarge,
            color = SleepCareTertiary,
        )
        Text(
            text = "더 나은 수면, 더 또렷한 공부 루틴",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = "졸음 감지와 실제 수면 데이터를 함께 분석해 오늘 밤의 수면 전략과 내일의 집중 타이밍을 제안합니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OnboardingFeatureCard(
            title = "실시간 졸음 기록",
            description = "라즈베리파이 디바이스에서 온 졸음 이벤트를 시간대별로 정리합니다.",
            icon = Icons.Default.Insights,
        )
        OnboardingFeatureCard(
            title = "수면 루틴 추천",
            description = "학습 플랜과 시험 일정까지 합쳐 권장 취침·기상 시간을 계산합니다.",
            icon = Icons.Default.Bedtime,
        )
        OnboardingFeatureCard(
            title = "기기 연결 허브",
            description = "스마트워치와 Raspberry Pi를 같은 앱 안에서 관리할 수 있게 준비합니다.",
            icon = Icons.Default.Devices,
        )
        Button(
            onClick = {
                viewModel.completeOnboarding()
                onStartClick()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("스마트 수면 케어 시작하기")
        }
    }
}

@Composable
private fun OnboardingFeatureCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = SleepCarePrimary)
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
        }
    }
}
