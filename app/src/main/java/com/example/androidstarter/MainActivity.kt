package com.example.androidstarter

import android.app.PendingIntent
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.androidstarter.ui.theme.AndroidStarterTheme
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.launch
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            var darkTheme by mutableStateOf(loadString(prefs, PREF_THEME_MODE, "dark") == "dark")
            AndroidStarterTheme(darkTheme = darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App(
                        darkTheme = darkTheme,
                        onThemeChange = {
                            darkTheme = it
                            saveString(prefs, PREF_THEME_MODE, if (it) "dark" else "light")
                        }
                    )
                }
            }
        }
    }
}

private const val PREFS_NAME = "embedded_toolkit"
private const val PREF_LOGS = "logs"
private const val PREF_USB_DEVICE = "usb_device"
private const val PREF_USB_BAUD = "usb_baud"
private const val PREF_USB_SEND = "usb_send"
private const val PREF_BLE_DEVICE = "ble_device"
private const val PREF_BLE_SEND = "ble_send"
private const val PREF_THEME_MODE = "theme_mode"
private const val ACTION_USB_PERMISSION = "com.example.androidstarter.USB_PERMISSION"
private val CCCD_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private enum class AppPage(val label: String, val icon: String) {
    Home("首页", "⌂"),
    Toolbox("工具箱", "▣"),
    Serial("串口", "◎"),
    Ble("BLE", "◍"),
    Settings("设置", "⚙");
}

private enum class ToolMode(val label: String) {
    TextToHex("文本转 Hex"),
    HexToText("Hex 转文本"),
}

private enum class SerialSendMode(val label: String) {
    Hex("HEX"),
    Ascii("ASCII"),
}

private enum class SerialReceiveMode(val label: String) {
    Hex("HEX"),
    Ascii("ASCII"),
}

private enum class NumberBase(val label: String, val radix: Int) {
    Bin("BIN", 2),
    Dec("DEC", 10),
    Hex("HEX", 16),
}

private data class BleDeviceUi(
    val name: String,
    val address: String,
    val rssi: Int,
)

private data class BleCharacteristicUi(
    val serviceUuid: String,
    val characteristicUuid: String,
    val properties: Int,
)

private data class UsbPreset(
    val name: String,
    val baudRate: String,
    val sendData: String,
)

private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasBlePermissions(context: Context): Boolean =
    blePermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun loadSavedLogs(prefs: SharedPreferences): List<String> {
    val raw = prefs.getString(PREF_LOGS, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        List(array.length()) { index -> array.optString(index) }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveLogs(prefs: SharedPreferences, logs: List<String>) {
    prefs.edit().putString(PREF_LOGS, JSONArray(logs).toString()).apply()
}

private fun saveString(prefs: SharedPreferences, key: String, value: String) {
    prefs.edit().putString(key, value).apply()
}

private fun loadString(prefs: SharedPreferences, key: String, fallback: String): String =
    prefs.getString(key, fallback) ?: fallback

private fun clearSavedState(prefs: SharedPreferences) {
    prefs.edit().clear().apply()
}

private fun formatLogText(title: String, logs: List<String>): String = buildString {
    appendLine(title)
    appendLine("Generated by Embedded Dev Toolkit")
    appendLine()
    logs.forEach { appendLine(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
) {
    var page by remember { mutableStateOf(AppPage.Home) }
    var toolMode by remember { mutableStateOf(ToolMode.TextToHex) }
    var toolInput by remember { mutableStateOf("Hello") }
    var crcInput by remember { mutableStateOf("01 03 00 00 00 0A") }
    var crc32Input by remember { mutableStateOf("01 03 00 00 00 0A") }
    var numberInput by remember { mutableStateOf("255") }
    var fromBase by remember { mutableStateOf(NumberBase.Dec) }

    val background = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.background,
        )
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("嵌入式开发工具包") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                AppPage.entries.forEach { item ->
                    NavigationBarItem(
                        selected = page == item,
                        onClick = { page = item },
                        icon = { Text(item.icon) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroCard()
                when (page) {
                    AppPage.Home -> HomePage(
                        onGoToolbox = { page = AppPage.Toolbox },
                        onGoSerial = { page = AppPage.Serial },
                        onGoBle = { page = AppPage.Ble },
                        onGoSettings = { page = AppPage.Settings },
                    )
                    AppPage.Toolbox -> ToolboxPage(
                        mode = toolMode,
                        onModeChange = { toolMode = it },
                        input = toolInput,
                        onInputChange = { toolInput = it },
                        crcInput = crcInput,
                        onCrcInputChange = { crcInput = it },
                        crc32Input = crc32Input,
                        onCrc32InputChange = { crc32Input = it },
                        numberInput = numberInput,
                        onNumberInputChange = { numberInput = it },
                        fromBase = fromBase,
                        onFromBaseChange = { fromBase = it },
                    )
                    AppPage.Serial -> SerialPage()
                    AppPage.Ble -> BlePage()
                    AppPage.Settings -> SettingsPage(
                        darkTheme = darkTheme,
                        onThemeChange = onThemeChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard() {
    ElevatedCard(
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "嵌入式开发工具包",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                "本地优先的串口、BLE 与协议工具面板",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("本地可用") })
                FilterChip(selected = true, onClick = {}, label = { Text("串口优先") })
                FilterChip(selected = true, onClick = {}, label = { Text("BLE 已接回") })
            }
        }
    }
}

@Composable
private fun HomePage(
    onGoToolbox: () -> Unit,
    onGoSerial: () -> Unit,
    onGoBle: () -> Unit,
    onGoSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("快速入口", style = MaterialTheme.typography.titleMedium)
        FeatureActionCard("工具箱", "Hex 转换 / CRC16 / CRC32 / 进制转换", "🧰", onGoToolbox)
        FeatureActionCard("串口调试", "真 USB 串口 / 日志 / HEX / ASCII", "🔌", onGoSerial)
        FeatureActionCard("BLE 调试", "扫描 / 连接 / 写入 / 通知 / 特征选择", "📡", onGoBle)
        FeatureActionCard("设置", "日志 / 数据 / 恢复默认", "⚙", onGoSettings)
    }
}

@Composable
private fun FeatureActionCard(
    title: String,
    subtitle: String,
    icon: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 5.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) {
                Text("打开")
            }
        }
    }
}

@Composable
private fun ToolboxPage(
    mode: ToolMode,
    onModeChange: (ToolMode) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    crcInput: String,
    onCrcInputChange: (String) -> Unit,
    crc32Input: String,
    onCrc32InputChange: (String) -> Unit,
    numberInput: String,
    onNumberInputChange: (String) -> Unit,
    fromBase: NumberBase,
    onFromBaseChange: (NumberBase) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolCard("Hex / Text") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolMode.entries.forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { onModeChange(item) },
                        label = { Text(item.label) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (mode == ToolMode.TextToHex) "输入文本" else "输入 Hex 字节") },
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val output = if (mode == ToolMode.TextToHex) textToHex(input) else hexToText(input)
            Text("结果: $output")
        }

        ToolCard("CRC16 / Modbus") {
            OutlinedTextField(
                value = crcInput,
                onValueChange = onCrcInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入十六进制字节") },
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("CRC16: ${crc16Modbus(crcInput)}")
        }

        ToolCard("CRC32 / Checksum") {
            OutlinedTextField(
                value = crc32Input,
                onValueChange = onCrc32InputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入十六进制字节") },
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("CRC32: ${crc32Hex(crc32Input)}")
            Text("SUM8: ${sum8Hex(crc32Input)}")
        }

        ToolCard("进制转换") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberBase.entries.forEach { base ->
                    FilterChip(
                        selected = fromBase == base,
                        onClick = { onFromBaseChange(base) },
                        label = { Text(base.label) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = numberInput,
                onValueChange = onNumberInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入数值") },
            )
            Spacer(modifier = Modifier.height(8.dp))
            val converted = convertNumberBase(numberInput, fromBase)
            Text("BIN: ${converted["BIN"] ?: "格式错误"}")
            Text("DEC: ${converted["DEC"] ?: "格式错误"}")
            Text("HEX: ${converted["HEX"] ?: "格式错误"}")
        }
    }
}

@Composable
private fun SerialPage() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val logs = remember { mutableStateListOf<String>() }
    var devices by remember { mutableStateOf(emptyList<UsbSerialDriver>()) }
    var selectedDeviceId by remember { mutableStateOf<Int?>(prefs.getString(PREF_USB_DEVICE, null)?.toIntOrNull()) }
    var baudRate by remember { mutableStateOf(loadString(prefs, PREF_USB_BAUD, "115200")) }
    var sendData by remember { mutableStateOf(loadString(prefs, PREF_USB_SEND, "01 03 00 00 00 0A")) }
    var sendMode by remember { mutableStateOf(SerialSendMode.Hex) }
    var receiveMode by remember { mutableStateOf(SerialReceiveMode.Hex) }
    var connection by remember { mutableStateOf<UsbSerialConnection?>(null) }
    var selectedPreset by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val presets = listOf(
        UsbPreset("Modbus 9600", "9600", "01 03 00 00 00 0A"),
        UsbPreset("UART 115200", "115200", "55 AA 01 02"),
        UsbPreset("AT 指令", "115200", "AT\r\n"),
    )

    fun postLog(message: String) {
        scope.launch {
            logs.add(message)
            saveLogs(prefs, logs)
        }
    }

    fun refreshDevices() {
        val found = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        devices = found
        if (selectedDeviceId == null && found.isNotEmpty()) {
            selectedDeviceId = found.first().device.deviceId
        }
    }

    LaunchedEffect(Unit) {
        logs.clear()
        logs.addAll(loadSavedLogs(prefs))
        refreshDevices()
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        postLog("USB 权限已授予")
                        openSelectedUsbDevice(
                            context = context,
                            usbManager = usbManager,
                            selectedDeviceId = selectedDeviceId,
                            baudRate = baudRate,
                            receiveMode = receiveMode,
                            postLog = ::postLog
                        ) { connection = it }
                    } else {
                        postLog("USB 权限被拒绝")
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            connection?.close()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolCard("USB 串口设备") {
            Button(onClick = {
                refreshDevices()
                postLog("已刷新串口设备列表")
            }) {
                Text("刷新设备")
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Text("未发现 USB 串口设备，请连接 OTG 串口模块。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { driver ->
                        FilterChip(
                            selected = selectedDeviceId == driver.device.deviceId,
                            onClick = {
                                selectedDeviceId = driver.device.deviceId
                                saveString(prefs, PREF_USB_DEVICE, driver.device.deviceId.toString())
                            },
                            label = {
                                Text("${driver.device.deviceName} / ${driver.device.productName ?: "Unknown"}")
                            }
                        )
                    }
                }
            }
        }

        ToolCard("常用预设") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset.name,
                        onClick = {
                            selectedPreset = preset.name
                            baudRate = preset.baudRate
                            sendData = preset.sendData
                            sendMode = if (preset.name == "AT 指令") SerialSendMode.Ascii else SerialSendMode.Hex
                            saveString(prefs, PREF_USB_BAUD, baudRate)
                            saveString(prefs, PREF_USB_SEND, sendData)
                            postLog("已应用预设: ${preset.name}")
                        },
                        label = { Text("${preset.name} / ${preset.baudRate}") }
                    )
                }
            }
        }

        ToolCard("串口参数") {
            OutlinedTextField(
                value = baudRate,
                onValueChange = {
                    baudRate = it.filter { ch -> ch.isDigit() }
                    saveString(prefs, PREF_USB_BAUD, baudRate)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("波特率") },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                openSelectedUsbDevice(
                    context = context,
                    usbManager = usbManager,
                    selectedDeviceId = selectedDeviceId,
                    baudRate = baudRate,
                    receiveMode = receiveMode,
                    postLog = ::postLog
                ) { connection = it }
            }) {
                Text("打开串口")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                connection?.close()
                connection = null
                postLog("串口已关闭")
            }) {
                Text("关闭串口")
            }
        }

        ToolCard("发送区") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SerialSendMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sendMode == mode,
                        onClick = { sendMode = mode },
                        label = { Text(mode.label) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = sendData,
                onValueChange = {
                    sendData = it
                    saveString(prefs, PREF_USB_SEND, sendData)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (sendMode == SerialSendMode.Hex) "发送 Hex" else "发送 ASCII") },
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val message = when {
                    connection == null -> "请先打开串口"
                    sendMode == SerialSendMode.Hex -> connection?.sendHex(sendData) ?: "请先打开串口"
                    else -> connection?.sendAscii(sendData) ?: "请先打开串口"
                }
                postLog(message)
            }) {
                Text("发送数据")
            }
        }

        ToolCard("接收显示") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SerialReceiveMode.entries.forEach { mode ->
                    FilterChip(
                        selected = receiveMode == mode,
                        onClick = { receiveMode = mode },
                        label = { Text(mode.label) },
                    )
                }
            }
            Text(
                if (receiveMode == SerialReceiveMode.Hex) {
                    "当前以十六进制显示接收数据"
                } else {
                    "当前以 ASCII 显示接收数据"
                }
            )
        }

        LogsCard("串口日志", logs)
        LogActionsCard("串口日志", logs) {
            logs.clear()
            saveLogs(prefs, logs)
        }
    }
}

@Composable
private fun BlePage() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val adapter = remember { bluetoothManager.adapter }
    val logs = remember { mutableStateListOf<String>() }
    val devices = remember { mutableStateMapOf<String, BleDeviceUi>() }
    var selectedAddress by remember { mutableStateOf(prefs.getString(PREF_BLE_DEVICE, null)) }
    var sendHex by remember { mutableStateOf(loadString(prefs, PREF_BLE_SEND, "AA 55 01 02")) }
    var connectState by remember { mutableStateOf("未连接") }
    var scanning by remember { mutableStateOf(false) }
    var connectedAddress by remember { mutableStateOf<String?>(null) }
    var gatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var characteristics by remember { mutableStateOf(emptyList<BleCharacteristicUi>()) }
    var selectedCharacteristicUuid by remember { mutableStateOf<String?>(null) }
    var notifyEnabled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun postLog(message: String) {
        scope.launch {
            logs.add(message)
            saveLogs(prefs, logs)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        postLog(if (granted) "BLE 权限已授予" else "BLE 权限被拒绝")
    }

    fun closeGatt() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        characteristics = emptyList()
        selectedCharacteristicUuid = null
        connectedAddress = null
        notifyEnabled = false
        connectState = "未连接"
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: device.address ?: "Unknown"
                val address = device.address ?: return
                devices[address] = BleDeviceUi(name = name, address = address, rssi = result.rssi)
            }
        }
    }

    fun connectSelectedDevice() {
        val address = selectedAddress ?: run {
            postLog("请先选择 BLE 设备")
            return
        }
        val remote = adapter?.getRemoteDevice(address) ?: run {
            postLog("无法获取目标设备")
            return
        }
        closeGatt()
        connectState = "连接中"
        postLog("正在连接 $address")
        gatt = remote.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedAddress = address
                    connectState = "已连接"
                    postLog("已连接 $address")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    postLog("连接断开 $address")
                    closeGatt()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val discovered = gatt.services.flatMap { service ->
                    service.characteristics.map { characteristic ->
                        BleCharacteristicUi(
                            serviceUuid = service.uuid.toString(),
                            characteristicUuid = characteristic.uuid.toString(),
                            properties = characteristic.properties,
                        )
                    }
                }
                characteristics = discovered
                selectedCharacteristicUuid = discovered.firstOrNull()?.characteristicUuid
                postLog("发现 ${discovered.size} 个特征")
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val value = characteristic.value ?: return
                val text = value.joinToString(" ") { "%02X".format(it) }
                postLog("[通知] ${characteristic.uuid}: $text")
            }
        })
    }

    fun writeSelectedCharacteristic() {
        val gattInstance = gatt ?: run {
            postLog("请先连接 BLE 设备")
            return
        }
        val characteristicUuid = selectedCharacteristicUuid ?: run {
            postLog("请先选择特征")
            return
        }
        val characteristic = gattInstance.services
            .flatMap { it.characteristics }
            .firstOrNull { it.uuid.toString() == characteristicUuid } ?: run {
            postLog("未找到目标特征")
            return
        }

        try {
            val bytes = sendHex.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { it.toInt(16).toByte() }
                .toByteArray()
            val writeType =
                if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
            if (Build.VERSION.SDK_INT >= 33) {
                val status = gattInstance.writeCharacteristic(characteristic, bytes, writeType)
                postLog(if (status == BluetoothStatusCodes.SUCCESS) "写入请求已提交" else "写入失败: $status")
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = bytes
                    val ok = gattInstance.writeCharacteristic(characteristic)
                    postLog(if (ok) "写入请求已提交" else "写入失败")
                }
            }
        } catch (e: Exception) {
            postLog("写入异常: ${e.message ?: "格式错误"}")
        }
    }

    fun toggleNotification() {
        val gattInstance = gatt ?: run {
            postLog("请先连接 BLE 设备")
            return
        }
        val characteristicUuid = selectedCharacteristicUuid ?: run {
            postLog("请先选择特征")
            return
        }
        val characteristic = gattInstance.services
            .flatMap { it.characteristics }
            .firstOrNull { it.uuid.toString() == characteristicUuid } ?: run {
            postLog("未找到目标特征")
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
            postLog("未找到通知描述符")
            return
        }
        val enable = !notifyEnabled
        val localOk = gattInstance.setCharacteristicNotification(characteristic, enable)
        val value = if (enable) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val status = gattInstance.writeDescriptor(descriptor, value)
            notifyEnabled = enable
            postLog("通知切换 local=$localOk remote=$status")
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                val ok = gattInstance.writeDescriptor(descriptor)
                notifyEnabled = enable
                postLog("通知切换 local=$localOk remote=$ok")
            }
        }
    }

    LaunchedEffect(Unit) {
        logs.clear()
        logs.addAll(loadSavedLogs(prefs))
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
            closeGatt()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolCard("BLE 状态") {
            Text(if (adapter == null) "设备不支持蓝牙" else "蓝牙可用")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                if (adapter == null) {
                    postLog("蓝牙不可用")
                    return@Button
                }
                if (!hasBlePermissions(context)) {
                    permissionLauncher.launch(blePermissions())
                    return@Button
                }
                if (scanning) {
                    adapter.bluetoothLeScanner?.stopScan(scanCallback)
                    scanning = false
                    postLog("停止扫描")
                } else {
                    devices.clear()
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                    adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
                    scanning = true
                    postLog("开始扫描")
                }
            }) {
                Text(if (scanning) "停止扫描" else "开始扫描")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("连接状态: $connectState")
            if (connectedAddress != null) {
                Text("当前设备: $connectedAddress")
            }
        }

        ToolCard("扫描结果") {
            if (devices.isEmpty()) {
                Text("暂无设备，请先扫描。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.values.forEach { device ->
                        FilterChip(
                            selected = selectedAddress == device.address,
                            onClick = {
                                selectedAddress = device.address
                                saveString(prefs, PREF_BLE_DEVICE, device.address)
                            },
                            label = { Text("${device.name} / ${device.address} / RSSI ${device.rssi}") }
                        )
                    }
                }
            }
        }

        ToolCard("设备连接") {
            Button(onClick = { connectSelectedDevice() }) {
                Text("连接设备")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                gatt?.disconnect()
                closeGatt()
                postLog("BLE 已断开")
            }) {
                Text("断开连接")
            }
        }

        ToolCard("特征与写入") {
            if (characteristics.isEmpty()) {
                Text("连接后会在这里显示特征。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    characteristics.forEach { item ->
                        FilterChip(
                            selected = selectedCharacteristicUuid == item.characteristicUuid,
                            onClick = { selectedCharacteristicUuid = item.characteristicUuid },
                            label = { Text("${item.characteristicUuid} / 属性 ${formatProperties(item.properties)}") }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = sendHex,
                onValueChange = {
                    sendHex = it
                    saveString(prefs, PREF_BLE_SEND, sendHex)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("发送 Hex") },
                minLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { writeSelectedCharacteristic() }) {
                Text("写入数据")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { toggleNotification() }) {
                Text(if (notifyEnabled) "关闭通知" else "开启通知")
            }
        }

        LogsCard("BLE 日志", logs)
        LogActionsCard("BLE 日志", logs) {
            logs.clear()
            saveLogs(prefs, logs)
        }
    }
}

@Composable
private fun SettingsPage(
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolCard("界面主题") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !darkTheme,
                    onClick = { onThemeChange(false) },
                    label = { Text("日间") },
                )
                FilterChip(
                    selected = darkTheme,
                    onClick = { onThemeChange(true) },
                    label = { Text("夜间") },
                )
            }
            Text(if (darkTheme) "当前为夜间模式" else "当前为日间模式")
        }

        ToolCard("本地数据") {
            Text("会清空日志和本地缓存。")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { clearSavedState(prefs) }) {
                Text("恢复默认")
            }
        }

        ToolCard("应用信息") {
            Text("嵌入式开发工具包")
            Text("当前状态：支持日间 / 夜间模式切换。")
        }
    }
}

@Composable
private fun ToolCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LogsCard(title: String, logs: List<String>) {
    ToolCard(title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (logs.isEmpty()) {
                Text("暂无日志")
            } else {
                logs.takeLast(8).forEach { line ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(line, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogActionsCard(
    title: String,
    logs: List<String>,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    ToolCard("日志操作") {
        Button(onClick = {
            val text = formatLogText(title, logs)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
        }) {
            Text("复制日志")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            val text = formatLogText(title, logs)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(share, "分享日志"))
        }) {
            Text("分享日志")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onClear) {
            Text("清空日志")
        }
    }
}

private class UsbSerialConnection(
    private val driver: UsbSerialDriver,
    private val manager: UsbManager,
    private val receiveMode: SerialReceiveMode,
    private val postLog: (String) -> Unit,
) {
    private var port: UsbSerialPort? = null
    private var deviceConnection: android.hardware.usb.UsbDeviceConnection? = null
    private var ioManager: SerialInputOutputManager? = null

    fun open(baudRate: Int): String {
        return try {
            val connection = manager.openDevice(driver.device) ?: return "无法打开 USB 设备"
            val openedPort = driver.ports.firstOrNull() ?: return "没有可用串口"
            openedPort.open(connection)
            openedPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            deviceConnection = connection
            port = openedPort
            ioManager = SerialInputOutputManager(openedPort, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val text = if (receiveMode == SerialReceiveMode.Hex) {
                        data.joinToString(" ") { "%02X".format(it) }
                    } else {
                        data.toString(Charsets.UTF_8)
                    }
                    val prefix = if (receiveMode == SerialReceiveMode.Hex) "[RX-HEX]" else "[RX-ASCII]"
                    postLog("$prefix $text")
                }

                override fun onRunError(e: Exception) {
                    postLog("串口接收错误: ${e.message ?: "unknown"}")
                }
            }).also { it.start() }
            "已连接 ${driver.device.deviceName}"
        } catch (e: Exception) {
            "连接失败: ${e.message ?: "unknown"}"
        }
    }

    fun sendHex(text: String): String {
        val openedPort = port ?: return "串口未打开"
        return try {
            val bytes = text
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { it.toInt(16).toByte() }
                .toByteArray()
            openedPort.write(bytes, 1000)
            "[TX-HEX] ${bytes.joinToString(" ") { "%02X".format(it) }}"
        } catch (e: Exception) {
            "发送失败: ${e.message ?: "格式错误"}"
        }
    }

    fun sendAscii(text: String): String {
        val openedPort = port ?: return "串口未打开"
        return try {
            val bytes = text.encodeToByteArray()
            openedPort.write(bytes, 1000)
            "[TX-ASCII] $text"
        } catch (e: Exception) {
            "发送失败: ${e.message ?: "格式错误"}"
        }
    }

    fun close() {
        try {
            ioManager?.stop()
        } catch (_: Exception) {
        }
        try {
            port?.close()
        } catch (_: Exception) {
        }
        try {
            deviceConnection?.close()
        } catch (_: Exception) {
        }
    }
}

private fun openSelectedUsbDevice(
    context: Context,
    usbManager: UsbManager,
    selectedDeviceId: Int?,
    baudRate: String,
    receiveMode: SerialReceiveMode,
    postLog: (String) -> Unit,
    onConnection: (UsbSerialConnection) -> Unit,
) {
    val driver = UsbSerialProber.getDefaultProber()
        .findAllDrivers(usbManager)
        .firstOrNull { it.device.deviceId == selectedDeviceId }

    if (driver == null) {
        postLog("未选中可用串口设备")
        return
    }

    if (!usbManager.hasPermission(driver.device)) {
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(driver.device, permissionIntent)
        postLog("正在请求 USB 权限")
        return
    }

    val connection = UsbSerialConnection(driver, usbManager, receiveMode, postLog)
    postLog(connection.open(baudRate.toIntOrNull() ?: 115200))
    onConnection(connection)
}

private fun formatProperties(properties: Int): String {
    val labels = buildList {
        if ((properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) add("READ")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) add("WRITE")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) add("WRITE_NR")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) add("NOTIFY")
        if ((properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) add("INDICATE")
    }
    return if (labels.isEmpty()) "NONE" else labels.joinToString("|")
}

private fun convertNumberBase(input: String, fromBase: NumberBase): Map<String, String> {
    return try {
        val normalized = input.trim()
        val value = normalized.toLong(fromBase.radix)
        mapOf(
            "BIN" to value.toString(2),
            "DEC" to value.toString(10),
            "HEX" to value.toString(16).uppercase(),
        )
    } catch (_: Exception) {
        emptyMap()
    }
}

private fun crc32Hex(input: String): String = try {
    val bytes = input
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { it.toInt(16).toByte() }
        .toByteArray()
    val crc = java.util.zip.CRC32()
    crc.update(bytes)
    "%08X".format(crc.value)
} catch (_: Exception) {
    "格式错误"
}

private fun sum8Hex(input: String): String = try {
    val bytes = input
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { it.toInt(16) and 0xFF }
    val sum = bytes.sum() and 0xFF
    "%02X".format(sum)
} catch (_: Exception) {
    "格式错误"
}

private fun textToHex(text: String): String =
    text.encodeToByteArray().joinToString(" ") { byte ->
        byte.toUByte().toString(16).uppercase().padStart(2, '0')
    }

private fun hexToText(input: String): String = try {
    val bytes = input
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { it.toInt(16).toByte() }
        .toByteArray()
    bytes.decodeToString()
} catch (_: Exception) {
    "格式错误"
}

private fun crc16Modbus(input: String): String = try {
    val bytes = input
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { it.toInt(16).toByte() }
        .toByteArray()

    var crc = 0xFFFF
    for (byte in bytes) {
        crc = crc xor (byte.toInt() and 0xFF)
        repeat(8) {
            crc = if ((crc and 1) != 0) {
                (crc ushr 1) xor 0xA001
            } else {
                crc ushr 1
            }
        }
    }
    val low = crc and 0xFF
    val high = (crc ushr 8) and 0xFF
    "%02X %02X".format(low, high)
} catch (_: Exception) {
    "格式错误"
}
