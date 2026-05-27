package com.gardenworld.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gardenworld.auto.automation.AutomationConfig
import com.gardenworld.auto.automation.AutomationState
import com.gardenworld.auto.automation.GameAutomationEngine
import com.gardenworld.auto.service.GardenAccessibilityService
import com.gardenworld.auto.ui.theme.GardenWorldAutoTheme
import com.gardenworld.auto.vision.ScreenCaptureManager
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : ComponentActivity() {
    
    private lateinit var automationEngine: GameAutomationEngine
    
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
        
        // 请求必要权限
        requestPermissions()
        
        setContent {
            GardenWorldAutoTheme {
                MainScreen(automationEngine)
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
    
    fun requestScreenCapture() {
        val intent = automationEngine.getScreenCaptureManager().getCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        automationEngine.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(automationEngine: GameAutomationEngine) {
    val context = LocalContext.current
    val activity = context as MainActivity
    
    // 状态
    val automationState by automationEngine.automationState.collectAsState()
    val currentTask by automationEngine.currentTask.collectAsState()
    val logMessages by automationEngine.logMessages.collectAsState()
    
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
                    // 无障碍服务状态指示器
                    val isAccessibilityRunning = GardenAccessibilityService.isRunning()
                    Icon(
                        imageVector = if (isAccessibilityRunning) 
                            Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "无障碍服务状态",
                        tint = if (isAccessibilityRunning) Color.Green else Color.Red,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // 状态卡片
            StatusCard(
                automationState = automationState,
                currentTask = currentTask,
                isInitialized = isInitialized,
                onInitialize = {
                    scope.launch {
                        isInitializing = true
                        activity.requestScreenCapture()
                        
                        // 等待屏幕捕获初始化
                        kotlinx.coroutines.delay(1000)
                        
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
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 控制按钮
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
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 配置区域
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
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 日志区域
            Text(
                "运行日志",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 日志列表
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    reverseLayout = false
                ) {
                    items(logMessages) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    automationState: AutomationState,
    currentTask: String,
    isInitialized: Boolean,
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
                    Text(
                        "状态",
                        style = MaterialTheme.typography.labelMedium
                    )
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
                        enabled = !isInitializing
                    ) {
                        if (isInitializing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
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
            
            if (currentTask.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "当前任务: $currentTask",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开始")
                }
            }
            
            AutomationState.RUNNING -> {
                Button(
                    onClick = onPause,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFA000)
                    )
                ) {
                    Icon(Icons.Default.Pause, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("暂停")
                }
                
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止")
                }
            }
            
            AutomationState.PAUSED -> {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("继续")
                }
                
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("停止")
                }
            }
        }
    }
}

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
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "自动化配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 功能开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    SwitchWithLabel(
                        label = "自动收获",
                        checked = autoHarvest,
                        onCheckedChange = onAutoHarvestChange
                    )
                    SwitchWithLabel(
                        label = "自动浇水",
                        checked = autoWater,
                        onCheckedChange = onAutoWaterChange
                    )
                }
                
                Column {
                    SwitchWithLabel(
                        label = "自动施肥",
                        checked = autoFertilize,
                        onCheckedChange = onAutoFertilizeChange
                    )
                    SwitchWithLabel(
                        label = "自动种植",
                        checked = autoPlant,
                        onCheckedChange = onAutoPlantChange
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SwitchWithLabel(
                label = "自动领取任务",
                checked = autoCollectTasks,
                onCheckedChange = onAutoCollectTasksChange
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 捕获间隔
            Text(
                "截图间隔: ${captureInterval.toInt()}秒",
                style = MaterialTheme.typography.bodyMedium
            )
            
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
fun SwitchWithLabel(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
