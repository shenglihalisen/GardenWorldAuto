package com.gardenworld.auto

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gardenworld.auto.automation.AutomationConfig
import com.gardenworld.auto.automation.AutomationState
import com.gardenworld.auto.automation.GameAutomationEngine
import com.gardenworld.auto.service.DownloadState
import com.gardenworld.auto.service.GardenAccessibilityService
import com.gardenworld.auto.service.ModelDownloadService
import com.gardenworld.auto.ui.theme.GardenWorldAutoTheme
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private lateinit var automationEngine: GameAutomationEngine
    private var modelDownloadService: ModelDownloadService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ModelDownloadService.LocalBinder
            modelDownloadService = binder.getService()
            isServiceBound = true
            Timber.d("ModelDownloadService bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            modelDownloadService = null
            isServiceBound = false
            Timber.d("ModelDownloadService disconnected")
        }
    }

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Timber.d("All permissions granted")
        } else {
            Timber.w("Some permissions denied")
        }
    }

    // 屏幕捕获请求
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            automationEngine.getScreenCaptureManager().initialize(result.resultCode, result.data!!)
            Timber.d("Screen capture initialized")
        } else {
            Timber.w("Screen capture permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        automationEngine = GameAutomationEngine(this)

        // 绑定模型下载服务
        bindService(
            Intent(this, ModelDownloadService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        requestPermissions()

        setContent {
            GardenWorldAutoTheme {
                MainScreen(
                    automationEngine = automationEngine,
                    modelDownloadService = modelDownloadService,
                    onRequestScreenCapture = { requestScreenCapture() }
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestScreenCapture() {
        val intent = automationEngine.getScreenCaptureManager().getCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        automationEngine.release()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    automationEngine: GameAutomationEngine,
    modelDownloadService: ModelDownloadService?,
    onRequestScreenCapture: () -> Unit
) {
    val context = LocalContext.current

    // 状态
    val automationState by automationEngine.automationState.collectAsState()
    val currentTask by automationEngine.currentTask.collectAsState()
    val logMessages by automationEngine.logMessages.collectAsState()

    // 模型下载状态
    val downloadState by modelDownloadService?.downloadState?.collectAsState() ?: mutableStateOf(DownloadState.Idle)
    val selectedModel by modelDownloadService?.selectedModel?.collectAsState() ?: mutableStateOf(ModelDownloadService.ModelOption.GEMMA_270M)

    // 配置状态
    var autoHarvest by remember { mutableStateOf(true) }
    var autoWater by remember { mutableStateOf(true) }
    var autoFertilize by remember { mutableStateOf(true) }
    var autoPlant by remember { mutableStateOf(true) }
    var autoCollectTasks by remember { mutableStateOf(true) }
    var captureInterval by remember { mutableStateOf(3f) }

    // 初始化状态
    var isInitialized by remember { mutableStateOf(false) }
    var isInitializing by remember { mutableStateOf(false) }

    // 模型是否就绪
    val isModelReady = downloadState is DownloadState.Completed

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 自动滚动日志
    LaunchedEffect(logMessages.size) {
        if (logMessages.isNotEmpty()) {
            listState.animateScrollToItem(logMessages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "花园世界自动化",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    val isAccessibilityRunning = GardenAccessibilityService.isRunning()
                    IconButton(onClick = {
                        // 打开无障碍设置
                        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }) {
                        Icon(
                            imageVector = if (isAccessibilityRunning)
                                Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = "无障碍服务状态",
                            tint = if (isAccessibilityRunning) Color.Green else Color.Red
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // ========== 1. AI模型下载卡片 ==========
            ModelDownloadCard(
                downloadState = downloadState,
                selectedModel = selectedModel,
                onSelectModel = { model ->
                    modelDownloadService?.selectModel(model)
                },
                onDownload = { model ->
                    modelDownloadService?.startDownload(model)
                },
                onCancel = {
                    modelDownloadService?.cancelDownload()
                },
                onRetry = { model ->
                    modelDownloadService?.startDownload(model)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ========== 2. 状态 + 初始化卡片 ==========
            StatusCard(
                automationState = automationState,
                currentTask = currentTask,
                isInitialized = isInitialized,
                isModelReady = isModelReady,
                onInitialize = {
                    scope.launch {
                        isInitializing = true
                        onRequestScreenCapture()
                        kotlinx.coroutines.delay(1500)
                        automationEngine.initialize().onSuccess {
                            isInitialized = true
                        }.onFailure {
                            isInitialized = false
                        }
                        isInitializing = false
                    }
                },
                isInitializing = isInitializing
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ========== 3. 控制按钮 ==========
            ControlButtons(
                automationState = automationState,
                isInitialized = isInitialized,
                onStart = {
                    val config = AutomationConfig(
                        captureInterval = (captureInterval * 1000).toLong(),
                        autoHarvest = autoHarvest,
                        autoWater = autoWater,
                        autoFertilize = autoFertilize,
                        autoPlant = autoPlant,
                        autoCollectTasks = autoCollectTasks
                    )
                    automationEngine.startAutomation(config)
                },
                onStop = { automationEngine.stopAutomation() },
                onPause = { automationEngine.pauseAutomation() },
                onResume = { automationEngine.resumeAutomation() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ========== 4. 配置区域 ==========
            ConfigSection(
                autoHarvest = autoHarvest,
                onAutoHarvestChange = { autoHarvest = it },
                autoWater = autoWater,
                onAutoWaterChange = { autoWater = it },
                autoFertilize = autoFertilize,
                onAutoFertilizeChange = { autoFertilize = it },
                autoPlant = autoPlant,
                onAutoPlantChange = { autoPlant = it },
                autoCollectTasks = autoCollectTasks,
                onAutoCollectTasksChange = { autoCollectTasks = it },
                captureInterval = captureInterval,
                onCaptureIntervalChange = { captureInterval = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ========== 5. 日志区域 ==========
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "运行日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${logMessages.size}条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E1E2E)
                )
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    reverseLayout = false
                ) {
                    items(logMessages) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            color = Color(0xFFA6E3A1),
                            modifier = Modifier.padding(vertical = 1.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ===================== 模型下载卡片 =====================

@Composable
fun ModelDownloadCard(
    downloadState: DownloadState,
    selectedModel: ModelDownloadService.ModelOption,
    onSelectModel: (ModelDownloadService.ModelOption) -> Unit,
    onDownload: (ModelDownloadService.ModelOption) -> Unit,
    onCancel: () -> Unit,
    onRetry: (ModelDownloadService.ModelOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (downloadState) {
                is DownloadState.Completed -> Color(0xFFE8F5E9)
                is DownloadState.Downloading -> Color(0xFFE3F2FD)
                is DownloadState.Error -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "AI模型",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 状态标签
                when (downloadState) {
                    is DownloadState.Completed -> {
                        AssistChip(
                            onClick = { },
                            label = { Text("已就绪", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp), tint = Color.Green)
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFFC8E6C9)
                            )
                        )
                    }
                    is DownloadState.Downloading -> {
                        AssistChip(
                            onClick = { },
                            label = { Text("下载中...", fontSize = 12.sp) },
                            leadingIcon = {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFFBBDEFB)
                            )
                        )
                    }
                    is DownloadState.Error -> {
                        AssistChip(
                            onClick = { },
                            label = { Text("失败", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Error, null, modifier = Modifier.size(16.dp), tint = Color.Red)
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFFFFCDD2)
                            )
                        )
                    }
                    else -> {
                        AssistChip(
                            onClick = { },
                            label = { Text("未下载", fontSize = 12.sp) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = Color(0xFFF5F5F5)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 模型选择下拉
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = "${selectedModel.name} (${selectedModel.fileSize})",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    enabled = downloadState !is DownloadState.Downloading
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    ModelDownloadService.ModelOption.values().forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${model.fileSize} · ${if (model.minSizeBytes > 1_000_000_000L) "效果最好" else "轻量快速"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onSelectModel(model)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 下载进度条
            when (downloadState) {
                is DownloadState.Downloading -> {
                    val progress = downloadState.progress
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "下载进度",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                "$progress%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFFE0E0E0),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "下载中，请保持网络连接...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is DownloadState.Error -> {
                    Text(
                        downloadState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC62828)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                is DownloadState.Completed -> {
                    val modelName = downloadState.model?.name ?: selectedModel.name
                    Text(
                        "✅ $modelName 已就绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF2E7D32)
                    )
                }
                else -> {
                    Text(
                        "请选择模型并点击下载（推荐Gemma 3 270M，约125MB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (downloadState) {
                    is DownloadState.Downloading -> {
                        OutlinedButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFC62828)
                            )
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("取消")
                        }
                    }
                    is DownloadState.Error -> {
                        Button(
                            onClick = { onRetry(selectedModel) }
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重试")
                        }
                    }
                    is DownloadState.Completed -> {
                        Text(
                            "可以开始使用了",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF2E7D32)
                        )
                    }
                    else -> {
                        Button(
                            onClick = { onDownload(selectedModel) }
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载模型")
                        }
                    }
                }
            }
        }
    }
}

// ===================== 状态卡片 =====================

@Composable
fun StatusCard(
    automationState: AutomationState,
    currentTask: String,
    isInitialized: Boolean,
    isModelReady: Boolean,
    onInitialize: () -> Unit,
    isInitializing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (automationState) {
                AutomationState.RUNNING -> Color(0xFFE8F5E9)
                AutomationState.PAUSED -> Color(0xFFFFF3E0)
                AutomationState.ERROR -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("运行状态", style = MaterialTheme.typography.labelMedium)
                    Text(
                        when (automationState) {
                            AutomationState.IDLE -> "⏹️ 未启动"
                            AutomationState.RUNNING -> "▶️ 运行中"
                            AutomationState.PAUSED -> "⏸️ 已暂停"
                            AutomationState.ERROR -> "❌ 错误"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (automationState) {
                            AutomationState.RUNNING -> Color(0xFF2E7D32)
                            AutomationState.PAUSED -> Color(0xFFEF6C00)
                            AutomationState.ERROR -> Color(0xFFC62828)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }

                if (!isInitialized) {
                    Button(
                        onClick = onInitialize,
                        enabled = !isInitializing && isModelReady
                    ) {
                        if (isInitializing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("初始化中")
                        } else {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("初始化")
                        }
                    }
                } else {
                    AssistChip(
                        onClick = { },
                        label = { Text("已就绪") },
                        leadingIcon = {
                            Icon(Icons.Default.CheckCircle, null, tint = Color.Green)
                        }
                    )
                }
            }

            if (!isModelReady && !isInitialized) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "⚠️ 请先下载AI模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEF6C00)
                )
            }

            if (currentTask.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "当前: $currentTask",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ===================== 控制按钮 =====================

@Composable
fun ControlButtons(
    automationState: AutomationState,
    isInitialized: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        when (automationState) {
            AutomationState.IDLE, AutomationState.ERROR -> {
                Button(
                    onClick = onStart,
                    enabled = isInitialized,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开始自动化")
                }
            }
            AutomationState.RUNNING -> {
                Button(
                    onClick = onPause,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000))
                ) {
                    Icon(Icons.Default.Pause, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("暂停")
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止")
                }
            }
            AutomationState.PAUSED -> {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("继续")
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止")
                }
            }
        }
    }
}

// ===================== 配置区域 =====================

@Composable
fun ConfigSection(
    autoHarvest: Boolean,
    onAutoHarvestChange: (Boolean) -> Unit,
    autoWater: Boolean,
    onAutoWaterChange: (Boolean) -> Unit,
    autoFertilize: Boolean,
    onAutoFertilizeChange: (Boolean) -> Unit,
    autoPlant: Boolean,
    onAutoPlantChange: (Boolean) -> Unit,
    autoCollectTasks: Boolean,
    onAutoCollectTasksChange: (Boolean) -> Unit,
    captureInterval: Float,
    onCaptureIntervalChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "自动化配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    SwitchWithLabel("自动收获", autoHarvest, onAutoHarvestChange)
                    SwitchWithLabel("自动浇水", autoWater, onAutoWaterChange)
                }
                Column {
                    SwitchWithLabel("自动施肥", autoFertilize, onAutoFertilizeChange)
                    SwitchWithLabel("自动种植", autoPlant, onAutoPlantChange)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            SwitchWithLabel("自动领取任务", autoCollectTasks, onAutoCollectTasksChange)

            Spacer(modifier = Modifier.height(8.dp))
            Text("截图间隔: ${captureInterval.toInt()}秒", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = captureInterval,
                onValueChange = onCaptureIntervalChange,
                valueRange = 1f..10f,
                steps = 8
            )
        }
    }
}

@Composable
fun SwitchWithLabel(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
