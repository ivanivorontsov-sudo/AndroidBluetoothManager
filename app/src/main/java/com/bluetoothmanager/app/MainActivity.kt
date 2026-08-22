package com.bluetoothmanager.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.reflect.Method

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.values.all { it }) {
            Toast.makeText(this, "Часть разрешений не выдана", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF64B5F6),
                    secondary = Color(0xFF81C784),
                    tertiary = Color(0xFFFFB74D),
                    background = Color(0xFF0D0D0D),
                    surface = Color(0xFF1A1A1A),
                    surfaceVariant = Color(0xFF252525)
                )
            ) {
                Surface(Modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DeviceManagerApp()
                }
            }
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        } else {
            permissions += Manifest.permission.BLUETOOTH
            permissions += Manifest.permission.BLUETOOTH_ADMIN
        }
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        val need = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) requestPermissionLauncher.launch(need.toTypedArray())
    }
}

data class BtDeviceInfo(
    val name: String,
    val address: String,
    val isConnected: Boolean,
    val isBonded: Boolean,
    val batteryLevel: Int = -1,
    val deviceClass: String = "",
    val majorClass: Int = 0,
    val uuids: List<String> = emptyList(),
    val icon: ImageVector = Icons.Default.Bluetooth,
    val type: String = "Classic"
)

data class UsbDeviceInfo(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String,
    val product: String,
    val deviceClass: Int,
    val interfaceCount: Int,
    val hasPermission: Boolean,
    val className: String
)

data class UiState(
    val btDevices: List<BtDeviceInfo> = emptyList(),
    val scannedDevices: List<BtDeviceInfo> = emptyList(),
    val usbDevices: List<UsbDeviceInfo> = emptyList(),
    val bluetoothEnabled: Boolean = false,
    val hasBtPermissions: Boolean = false,
    val isScanning: Boolean = false,
    val selectedBtDevice: BtDeviceInfo? = null,
    val selectedUsbDevice: UsbDeviceInfo? = null,
    val statusMessage: String = ""
)

class DeviceViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var appContext: Context? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { addScannedDevice(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                    _uiState.update { it.copy(isScanning = false) }
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshAll()
            }
        }
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }

        bluetoothAdapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy
                refreshBluetooth()
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) a2dpProxy = null
            }
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) headsetProxy = proxy
                refreshBluetooth()
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) headsetProxy = null
            }
        }, BluetoothProfile.HEADSET)

        refreshAll()
    }

    fun refreshAll() {
        refreshBluetooth()
        refreshUsb()
    }

    @SuppressLint("MissingPermission")
    fun refreshBluetooth() {
        viewModelScope.launch {
            val ctx = appContext ?: return@launch
            val adapter = bluetoothAdapter
            val hasPerms = checkBtPermissions(ctx)
            val enabled = adapter?.isEnabled == true
            if (!hasPerms || adapter == null) {
                _uiState.update {
                    it.copy(hasBtPermissions = hasPerms, bluetoothEnabled = enabled, btDevices = emptyList())
                }
                return@launch
            }
            val connectedAddrs = mutableSetOf<String>()
            try {
                a2dpProxy?.connectedDevices?.forEach { connectedAddrs += it.address }
                headsetProxy?.connectedDevices?.forEach { connectedAddrs += it.address }
            } catch (_: SecurityException) {}
            val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: SecurityException) { emptySet() }
            val devices = bonded.map { toBtInfo(it, connectedAddrs.contains(it.address), true) }
                .sortedWith(compareByDescending<BtDeviceInfo> { it.isConnected }.thenBy { it.name })
            _uiState.update {
                it.copy(btDevices = devices, bluetoothEnabled = enabled, hasBtPermissions = true)
            }
        }
    }

    fun refreshUsb() {
        viewModelScope.launch {
            val ctx = appContext ?: return@launch
            val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val list = usbManager.deviceList.values.map { dev ->
                UsbDeviceInfo(
                    deviceId = dev.deviceId,
                    name = dev.deviceName,
                    vendorId = dev.vendorId,
                    productId = dev.productId,
                    manufacturer = dev.manufacturerName ?: "—",
                    product = dev.productName ?: "USB устройство",
                    deviceClass = dev.deviceClass,
                    interfaceCount = dev.interfaceCount,
                    hasPermission = usbManager.hasPermission(dev),
                    className = usbClassName(dev.deviceClass)
                )
            }
            _uiState.update { it.copy(usbDevices = list) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun toBtInfo(device: BluetoothDevice, isConnected: Boolean, bonded: Boolean): BtDeviceInfo {
        val name = try { device.name ?: "Неизвестное" } catch (_: SecurityException) { "Неизвестное" }
        val uuids = try { device.uuids?.map { it.uuid.toString() } ?: emptyList() } catch (_: Exception) { emptyList() }
        val major = try { device.bluetoothClass?.majorDeviceClass ?: 0 } catch (_: Exception) { 0 }
        val classStr = try {
            when (device.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> "Аудио / Наушники"
                BluetoothClass.Device.Major.PHONE -> "Телефон"
                BluetoothClass.Device.Major.COMPUTER -> "Компьютер"
                BluetoothClass.Device.Major.WEARABLE -> "Носимое"
                BluetoothClass.Device.Major.PERIPHERAL -> "Периферия"
                else -> "Другое"
            }
        } catch (_: Exception) { "—" }
        return BtDeviceInfo(
            name = name,
            address = device.address,
            isConnected = isConnected,
            isBonded = bonded,
            batteryLevel = tryGetBatteryLevel(device),
            deviceClass = classStr,
            majorClass = major,
            uuids = uuids,
            icon = when (major) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> Icons.Default.Headphones
                BluetoothClass.Device.Major.PHONE -> Icons.Default.Phone
                else -> Icons.Default.Bluetooth
            },
            type = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 &&
                    device.type == BluetoothDevice.DEVICE_TYPE_LE) "BLE" else "Classic"
            } catch (_: Exception) { "Classic" }
        )
    }

    private fun usbClassName(cls: Int): String = when (cls) {
        UsbConstants.USB_CLASS_AUDIO -> "Аудио"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "Накопитель"
        UsbConstants.USB_CLASS_HUB -> "Хаб"
        UsbConstants.USB_CLASS_VIDEO -> "Видео"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor"
        else -> "Класс $cls"
    }

    private fun tryGetBatteryLevel(device: BluetoothDevice): Int = try {
        val method: Method = device.javaClass.getMethod("getBatteryLevel")
        val level = method.invoke(device) as? Int ?: -1
        if (level in 0..100) level else -1
    } catch (_: Exception) { -1 }

    private fun checkBtPermissions(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else true

    @SuppressLint("MissingPermission")
    private fun addScannedDevice(device: BluetoothDevice) {
        val info = toBtInfo(device, false, false)
        _uiState.update { state ->
            val exists = state.scannedDevices.any { it.address == info.address } ||
                    state.btDevices.any { it.address == info.address }
            if (exists) state else state.copy(scannedDevices = state.scannedDevices + info)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        viewModelScope.launch {
            try {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                _uiState.update { it.copy(isScanning = true, scannedDevices = emptyList()) }
                adapter.startDiscovery()
                delay(12000)
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                _uiState.update { it.copy(isScanning = false) }
            } catch (_: SecurityException) {
                showMsg("Нет разрешения на сканирование")
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun stopScan() {
        try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
        _uiState.update { it.copy(isScanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(address: String) {
        viewModelScope.launch {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(address) ?: return@launch
                showMsg(if (device.createBond()) "Запрос сопряжения…" else "Не удалось")
                delay(2000)
                refreshBluetooth()
            } catch (_: SecurityException) { showMsg("Нет разрешения") }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(info: BtDeviceInfo) {
        viewModelScope.launch {
            val ctx = appContext ?: return@launch
            try {
                val device = bluetoothAdapter?.getRemoteDevice(info.address) ?: return@launch
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    device.createBond()
                    showMsg("Сопряжение…")
                    delay(3000)
                }
                var ok = false
                try { device.javaClass.getMethod("connect").invoke(device); ok = true } catch (_: Exception) {}
                try {
                    a2dpProxy?.javaClass?.getMethod("connect", BluetoothDevice::class.java)
                        ?.invoke(a2dpProxy, device)
                    ok = true
                } catch (_: Exception) {}
                try {
                    headsetProxy?.javaClass?.getMethod("connect", BluetoothDevice::class.java)
                        ?.invoke(headsetProxy, device)
                    ok = true
                } catch (_: Exception) {}
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.mode = AudioManager.MODE_NORMAL
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    am.isSpeakerphoneOn = false
                } catch (_: Exception) {}
                showMsg(if (ok) "Команда подключения отправлена" else "Попытка подключения…")
                delay(1500)
                refreshBluetooth()
            } catch (e: SecurityException) {
                showMsg("Нет разрешения BLUETOOTH_CONNECT")
            } catch (e: Exception) {
                showMsg("Ошибка: ${e.message?.take(40)}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(info: BtDeviceInfo) {
        viewModelScope.launch {
            val ctx = appContext ?: return@launch
            try {
                val device = bluetoothAdapter?.getRemoteDevice(info.address) ?: return@launch
                try { device.javaClass.getMethod("disconnect").invoke(device) } catch (_: Exception) {}
                try {
                    a2dpProxy?.javaClass?.getMethod("disconnect", BluetoothDevice::class.java)
                        ?.invoke(a2dpProxy, device)
                } catch (_: Exception) {}
                try {
                    headsetProxy?.javaClass?.getMethod("disconnect", BluetoothDevice::class.java)
                        ?.invoke(headsetProxy, device)
                } catch (_: Exception) {}
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                } catch (_: Exception) {}
                showMsg("Команда отключения отправлена")
                delay(1200)
                refreshBluetooth()
            } catch (e: Exception) {
                showMsg("Ошибка: ${e.message?.take(40)}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun unpairDevice(info: BtDeviceInfo) {
        viewModelScope.launch {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(info.address) ?: return@launch
                val result = device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
                showMsg(if (result) "Устройство забыто" else "Не удалось забыть")
                delay(800)
                refreshBluetooth()
            } catch (e: Exception) {
                showMsg("Ошибка: ${e.message?.take(40)}")
            }
        }
    }

    fun selectBt(d: BtDeviceInfo?) = _uiState.update { it.copy(selectedBtDevice = d) }
    fun selectUsb(d: UsbDeviceInfo?) = _uiState.update { it.copy(selectedUsbDevice = d) }

    fun openSystemBluetoothSettings() {
        appContext?.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openSystemSoundSettings() {
        appContext?.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openSystemSettings() {
        appContext?.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun showMsg(msg: String) {
        _uiState.update { it.copy(statusMessage = msg) }
        appContext?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            appContext?.unregisterReceiver(receiver)
            bluetoothAdapter?.let { a ->
                a2dpProxy?.let { a.closeProfileProxy(BluetoothProfile.A2DP, it) }
                headsetProxy?.let { a.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            }
        } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagerApp(vm: DeviceViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.init(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Device Manager", fontWeight = FontWeight.Bold)
                        if (state.statusMessage.isNotEmpty()) {
                            Text(state.statusMessage, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(Icons.Default.Refresh, "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("BT") },
                    icon = { Icon(Icons.Default.Bluetooth, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("USB") },
                    icon = { Icon(Icons.Default.Star, null) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Аудио") },
                    icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Ещё") },
                    icon = { Icon(Icons.Default.Settings, null) })
            }
            when (tab) {
                0 -> BluetoothTab(state, vm)
                1 -> UsbTab(state, vm)
                2 -> AudioTab()
                3 -> ExtraSettingsTab(vm)
            }
        }
    }

    state.selectedBtDevice?.let { BtDeviceSheet(it, vm) { vm.selectBt(null) } }
    state.selectedUsbDevice?.let { UsbDeviceSheet(it) { vm.selectUsb(null) } }
}

@Composable
fun BluetoothTab(state: UiState, vm: DeviceViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                if (state.bluetoothEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                null,
                tint = if (state.bluetoothEnabled) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !state.hasBtPermissions -> "Нет разрешений"
                    state.bluetoothEnabled -> "Bluetooth включён"
                    else -> "Bluetooth выключен"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier.weight(1f))
            if (state.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.stopScan() }) { Text("Стоп") }
            } else {
                FilledTonalButton(onClick = { vm.startScan() }) {
                    Icon(Icons.Default.BluetoothSearching, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Скан")
                }
            }
        }
        Spacer(modifier.height(12.dp))
        if (!state.hasBtPermissions) {
            EmptyState(Icons.Default.Lock, "Выдайте Bluetooth и Location")
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Спаренные (${state.btDevices.size})",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (state.btDevices.isEmpty()) {
                item { Text("Нет спаренных устройств", color = Color.Gray) }
            }
            items(state.btDevices, key = { it.address }) { dev ->
                BtDeviceCard(dev,
                    onClick = { vm.selectBt(dev) },
                    onConnect = { vm.connectDevice(dev) },
                    onDisconnect = { vm.disconnectDevice(dev) })
            }
            if (state.scannedDevices.isNotEmpty() || state.isScanning) {
                item {
                    Spacer(modifier.height(8.dp))
                    Text("Найденные (${state.scannedDevices.size})",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(state.scannedDevices, key = { "s_${it.address}" }) { dev ->
                    BtDeviceCard(dev,
                        onClick = { vm.selectBt(dev) },
                        onConnect = { vm.pairDevice(dev.address) },
                        onDisconnect = {},
                        isScanned = true)
                }
            }
        }
    }
}

@Composable
fun BtDeviceCard(
    device: BtDeviceInfo,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    isScanned: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isConnected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(device.icon, null, modifier = Modifier.size(36.dp),
                tint = if (device.isConnected) MaterialTheme.colorScheme.primary else Color.Gray)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${device.deviceClass} · ${device.type}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(device.address, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    when {
                        device.isConnected -> "● Подключено"
                        device.isBonded -> "Спарено"
                        else -> "Не спарено"
                    } + if (device.batteryLevel in 0..100) " · ${device.batteryLevel}%" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.isConnected) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
            if (isScanned) {
                Button(onClick = onConnect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Спарить")
                }
            } else if (device.isConnected) {
                FilledTonalButton(onClick = onDisconnect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Откл.")
                }
            } else {
                Button(onClick = onConnect, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Подкл.")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtDeviceSheet(device: BtDeviceInfo, vm: DeviceViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier.height(8.dp))
            DetailRow("Адрес", device.address)
            DetailRow("Тип", device.type)
            DetailRow("Класс", device.deviceClass)
            DetailRow("Статус", when {
                device.isConnected -> "Подключено"
                device.isBonded -> "Спарено"
                else -> "Не спарено"
            })
            if (device.batteryLevel in 0..100) DetailRow("Батарея", "${device.batteryLevel}%")
            if (device.uuids.isNotEmpty()) {
                Text("UUID", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                device.uuids.take(6).forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            Spacer(modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device.isConnected) {
                    Button(onClick = { vm.disconnectDevice(device); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("Отключить")
                    }
                } else {
                    Button(onClick = { vm.connectDevice(device); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("Подключить")
                    }
                }
                if (device.isBonded) {
                    OutlinedButton(onClick = { vm.unpairDevice(device); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("Забыть")
                    }
                } else {
                    OutlinedButton(onClick = { vm.pairDevice(device.address); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("Спарить")
                    }
                }
            }
            Spacer(modifier.height(24.dp))
        }
    }
}

@Composable
fun UsbTab(state: UiState, vm: DeviceViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, null)
            Spacer(Modifier.width(8.dp))
            Text("USB (${state.usbDevices.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier.weight(1f))
            IconButton(onClick = { vm.refreshUsb() }) { Icon(Icons.Default.Refresh, null) }
        }
        Spacer(modifier.height(12.dp))
        if (state.usbDevices.isEmpty()) {
            EmptyState(Icons.Default.Info, "Нет USB-устройств\nПодключите OTG / наушники / флешку")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.usbDevices, key = { it.deviceId }) { dev ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectUsb(dev) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(dev.product, fontWeight = FontWeight.Medium)
                            Text(dev.manufacturer, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text("${dev.className} · VID:0x${dev.vendorId.toString(16)} PID:0x${dev.productId.toString(16)}",
                                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(
                                if (dev.hasPermission) "● Доступ есть" else "Нужно разрешение",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dev.hasPermission) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDeviceSheet(device: UsbDeviceInfo, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(device.product, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier.height(8.dp))
            DetailRow("Производитель", device.manufacturer)
            DetailRow("Имя", device.name)
            DetailRow("Класс", device.className)
            DetailRow("VID", "0x${device.vendorId.toString(16).uppercase()}")
            DetailRow("PID", "0x${device.productId.toString(16).uppercase()}")
            DetailRow("Интерфейсов", "${device.interfaceCount}")
            DetailRow("Разрешение", if (device.hasPermission) "Есть" else "Нет")
            Spacer(modifier.height(24.dp))
        }
    }
}

@Composable
fun AudioTab() {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    fun vol(stream: Int): Float {
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return am.getStreamVolume(stream).toFloat() / max
    }
    var media by remember { mutableFloatStateOf(vol(AudioManager.STREAM_MUSIC)) }
    var call by remember { mutableFloatStateOf(vol(AudioManager.STREAM_VOICE_CALL)) }
    var ring by remember { mutableFloatStateOf(vol(AudioManager.STREAM_RING)) }
    var notif by remember { mutableFloatStateOf(vol(AudioManager.STREAM_NOTIFICATION)) }
    var alarm by remember { mutableFloatStateOf(vol(AudioManager.STREAM_ALARM)) }
    var speaker by remember { mutableStateOf(am.isSpeakerphoneOn) }
    var sco by remember { mutableStateOf(am.isBluetoothScoOn) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Громкость", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        VolumeSlider("Медиа", media) {
            media = it
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
        }
        VolumeSlider("Звонок", call) {
            call = it
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)).toInt(), 0)
        }
        VolumeSlider("Рингтон", ring) {
            ring = it
            am.setStreamVolume(AudioManager.STREAM_RING,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_RING)).toInt(), 0)
        }
        VolumeSlider("Уведомления", notif) {
            notif = it
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)).toInt(), 0)
        }
        VolumeSlider("Будильник", alarm) {
            alarm = it
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_ALARM)).toInt(), 0)
        }
        HorizontalDivider()
        Text("Маршрутизация", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Динамик", modifier = Modifier.weight(1f))
            Switch(checked = speaker, onCheckedChange = { speaker = it; am.isSpeakerphoneOn = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bluetooth SCO", modifier = Modifier.weight(1f))
            Switch(checked = sco, onCheckedChange = {
                sco = it
                if (it) { am.startBluetoothSco(); am.isBluetoothScoOn = true }
                else { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
            })
        }
    }
}

@Composable
fun VolumeSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()}%")
        }
        Slider(value = value, onValueChange = onChange)
    }
}

@Composable
fun ExtraSettingsTab(vm: DeviceViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Системные настройки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { vm.openSystemBluetoothSettings() }) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, null)
                Spacer(Modifier.width(16.dp))
                Text("Bluetooth", modifier = Modifier.weight(1f))
            }
        }
        OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { vm.openSystemSoundSettings() }) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Spacer(Modifier.width(16.dp))
                Text("Звук", modifier = Modifier.weight(1f))
            }
        }
        OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { vm.openSystemSettings() }) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(16.dp))
                Text("Все настройки", modifier = Modifier.weight(1f))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            "Подключение/отключение через hidden API (connect/disconnect/removeBond). " +
                "На части прошивок Android может игнорировать команду — это ограничение системы, не приложения.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(110.dp), color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, message: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(64.dp), tint = Color.Gray.copy(alpha = 0.5f))
        Spacer(modifier.height(16.dp))
        Text(message, color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}
