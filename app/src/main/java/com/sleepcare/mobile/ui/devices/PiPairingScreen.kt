package com.sleepcare.mobile.ui.devices

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sleepcare.mobile.data.source.PiPairingCodec
import com.sleepcare.mobile.domain.DeviceConnectionRepository
import com.sleepcare.mobile.domain.PiPairingPayload
import com.sleepcare.mobile.ui.components.GlassCard
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PiPairingUiState(
    val rawPayload: String? = null,
    val parsedPayload: PiPairingPayload? = null,
    val errorMessage: String? = null,
    val registered: Boolean = false,
)

@Composable
fun PiPairingScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    viewModel: PiPairingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(uiState.registered) {
        if (uiState.registered) onBack()
    }

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pi QR 등록", style = MaterialTheme.typography.headlineMedium)
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SPKI fingerprint 등록", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pi 화면의 QR 코드를 스캔하면 이 앱이 해당 Pi의 TLS 공개키를 신뢰하도록 등록합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!hasCameraPermission) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                Text("카메라 권한 허용")
            }
        } else if (uiState.parsedPayload == null) {
            QrScannerView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                onQrScanned = viewModel::onQrScanned,
            )
        }

        uiState.errorMessage?.let { message ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("QR을 확인할 수 없습니다", style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = viewModel::clearScan, modifier = Modifier.fillMaxWidth()) {
                        Text("다시 스캔")
                    }
                }
            }
        }

        uiState.parsedPayload?.let { payload ->
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(payload.displayName ?: payload.deviceId, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "device_id: ${payload.deviceId}\nservice: ${payload.service}\nws: ${payload.ws}\nSPKI: ${PiPairingCodec.shortPin(payload.spkiSha256)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = viewModel::registerScannedPi, modifier = Modifier.fillMaxWidth()) {
                        Text("이 Pi 등록")
                    }
                    OutlinedButton(onClick = viewModel::clearScan, modifier = Modifier.fillMaxWidth()) {
                        Text("다시 스캔")
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("뒤로")
        }
    }
}

@HiltViewModel
class PiPairingViewModel @Inject constructor(
    private val deviceConnectionRepository: DeviceConnectionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PiPairingUiState())
    val uiState = _uiState.asStateFlow()

    fun onQrScanned(rawPayload: String) {
        if (_uiState.value.rawPayload == rawPayload && _uiState.value.parsedPayload != null) return
        val parsed = runCatching { PiPairingCodec.parse(rawPayload) }
        _uiState.value = parsed.fold(
            onSuccess = { PiPairingUiState(rawPayload = rawPayload, parsedPayload = it) },
            onFailure = { PiPairingUiState(rawPayload = rawPayload, errorMessage = it.message ?: "지원하지 않는 QR입니다.") },
        )
    }

    fun clearScan() {
        _uiState.value = PiPairingUiState()
    }

    fun registerScannedPi() {
        val rawPayload = _uiState.value.rawPayload ?: return
        viewModelScope.launch {
            val result = deviceConnectionRepository.registerPiFromQr(rawPayload)
            _uiState.update { current ->
                if (result.isSuccess) {
                    current.copy(registered = true)
                } else {
                    current.copy(errorMessage = result.exceptionOrNull()?.message ?: "Pi 등록에 실패했습니다.")
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrScannerView(
    modifier: Modifier = Modifier,
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val processing = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(viewContext)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply {
                            setAnalyzer(executor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage == null || !processing.compareAndSet(false, true)) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onQrScanned)
                                    }
                                    .addOnCompleteListener {
                                        processing.set(false)
                                        imageProxy.close()
                                    }
                            }
                        }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                },
                ContextCompat.getMainExecutor(context),
            )
            previewView
        },
    )
}
