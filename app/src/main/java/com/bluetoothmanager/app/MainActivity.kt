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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
            Toast.makeText(this, "Часть разрешений не выдана — функции будут ограничены", Toast.LENGTH_LONG).show()
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
        if (need.isNotEmpty()) {
            requestPermissionLauncher.launch(need.toTypedArray())
        }
    }
}

/* ===================== DATA ===================== */

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
    val subclass: Int,
    val protocol: Int,
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

/* ===================== VIEWMODEL ===================== */

class DeviceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var context: Context? = null
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
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _uiState.update { it.copy(isScanning = false) }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshAll()
                }
            }
        }
    }

    fun init(ctx: Context) {
        context = ctx.applicationContext
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
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Profile proxies for connection state + control
        bluetoothAdapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dpProxy = proxy
                    BluetoothProfile.HEADSET -> headsetProxy = proxy
                }
                refreshBluetooth()
            }
            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    BluetoothProfile.A2DP -> a2dpProxy = null
                    BluetoothProfile.HEADSET -> headsetProxy = null
                }
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
            val ctx = context ?: return@launch
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

            val devices = bonded.map { dev ->
                toBtInfo(dev, connectedAddrs.contains(dev.address), bonded = true)
            }.sortedWith(compareByDescending<BtDeviceInfo> { it.isConnected }.thenBy { it.name })

            _uiState.update {
                it.copy(
                    btDevices = devices,
                    bluetoothEnabled = enabled,
                    hasBtPermissions = true
                )
            }
        }
    }

    fun refreshUsb() {
        viewModelScope.launch {
            val ctx = context ?: return@launch
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
                    subclass = dev.deviceSubclass,
                    protocol = dev.deviceProtocol,
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
        val uuids = try {
            device.uuids?.map { it.uuid.toString() } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val major = try { device.bluetoothClass?.majorDeviceClass ?: 0 } catch (_: Exception) { 0 }
        val classStr = try {
            val c = device.bluetoothClass
            when (c?.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> "Аудио / Наушники"
                BluetoothClass.Device.Major.PHONE -> "Телефон"
                BluetoothClass.Device.Major.COMPUTER -> "Компьютер"
                BluetoothClass.Device.Major.WEARABLE -> "Носимое"
                BluetoothClass.Device.Major.PERIPHERAL -> "Периферия"
                BluetoothClass.Device.Major.IMAGING -> "Изображение"
                BluetoothClass.Device.Major.HEALTH -> "Здоровье"
                else -> "Другое"
            }
        } catch (_: Exception) { "—" }

        val battery = tryGetBatteryLevel(device)

        return BtDeviceInfo(
            name = name,
            address = device.address,
            isConnected = isConnected,
            isBonded = bonded,
            batteryLevel = battery,
            deviceClass = classStr,
            majorClass = major,
            uuids = uuids,
            icon = iconForMajor(major),
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 &&
                try { device.type == BluetoothDevice.DEVICE_TYPE_LE } catch (_: Exception) { false }
            ) "BLE" else "Classic"
        )
    }

    private fun iconForMajor(major: Int): ImageVector = when (major) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> Icons.Default.Headphones
        BluetoothClass.Device.Major.PHONE -> Icons.Default.Phone
        BluetoothClass.Device.Major.COMPUTER -> Icons.Default.Computer
        BluetoothClass.Device.Major.WEARABLE -> Icons.Default.Watch
        BluetoothClass.Device.Major.PERIPHERAL -> Icons.Default.Keyboard
        else -> Icons.Default.Bluetooth
    }

    private fun usbClassName(cls: Int): String = when (cls) {
        UsbConstants.USB_CLASS_AUDIO -> "Аудио"
        UsbConstants.USB_CLASS_COMM -> "Коммуникация"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_PRINTER -> "Принтер"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "Накопитель"
        UsbConstants.USB_CLASS_HUB -> "Хаб"
        UsbConstants.USB_CLASS_VIDEO -> "Видео"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "Беспроводной"
        UsbConstants.USB_CLASS_MISC -> "Разное"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "Vendor"
        else -> "Класс $cls"
    }

    private fun tryGetBatteryLevel(device: BluetoothDevice): Int {
        return try {
            // Hidden API via reflection (works on many devices)
            val method: Method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int ?: -1
            if (level in 0..100) level else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun checkBtPermissions(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    private fun addScannedDevice(device: BluetoothDevice) {
        val info = toBtInfo(device, isConnected = false, bonded = false)
        _uiState.update { state ->
            val exists = state.scannedDevices.any { it.address == info.address } ||
                    state.btDevices.any { it.address == info.address }
            if (exists) state
            else state.copy(scannedDevices = state.scannedDevices + info)
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
            } catch (e: SecurityException) {
                showMsg("Нет разрешения на сканирование")
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: SecurityException) {}
        _uiState.update { it.copy(isScanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(address: String) {
        viewModelScope.launch {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(address) ?: return@launch
                val result = device.createBond()
                showMsg(if (result) "Запрос на сопряжение отправлен…" else "Не удалось начать сопряжение")
                delay(2000)
                refreshBluetooth()
            } catch (e: SecurityException) {
                showMsg("Нет разрешения")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(info: BtDeviceInfo) {
        viewModelScope.launch {
            val ctx = context ?: return@launch
            try {
                val device = bluetoothAdapter?.getRemoteDevice(info.address) ?: return@launch

                // 1. Ensure bonded
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    device.createBond()
                    showMsg("Сопряжение…")
                    delay(3000)
                }

                // 2. Try hidden connect methods via reflection (works on many firmwares)
                var connected = false
                try {
                    val method = device.javaClass.getMethod("connect")
                    method.invoke(device)
                    connected = true
                } catch (_: Exception) {}

                // 3. A2DP / Headset connect via reflection on proxy
                try {
                    a2dpProxy?.let { proxy ->
                        val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        m.invoke(proxy, device)
                        connected = true
                    }
                } catch (_: Exception) {}

                try {
                    headsetProxy?.let { proxy ->
                        val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        m.invoke(proxy, device)
                        connected = true
                    }
                } catch (_: Exception) {}

                // 4. AudioManager force Bluetooth
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.mode = AudioManager.MODE_NORMAL
                    am.isBluetoothScoOn = true
                    am.startBluetoothSco()
                    am.isSpeakerphoneOn = false
                } catch (_: Exception) {}

                showMsg(if (connected) "Команда подключения отправлена" else "Попытка подключения…")
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
            val ctx = context ?: return@launch
            try {
                val device = bluetoothAdapter?.getRemoteDevice(info.address) ?: return@launch

                // Reflection disconnect on device
                try {
                    val method = device.javaClass.getMethod("disconnect")
                    method.invoke(device)
                } catch (_: Exception) {}

                // A2DP / Headset disconnect
                try {
                    a2dpProxy?.let { proxy ->
                        val m = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        m.invoke(proxy, device)
                    }
                } catch (_: Exception) {}

                try {
                    headsetProxy?.let { proxy ->
                        val m = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        m.invoke(proxy, device)
                    }
                } catch (_: Exception) {}

                // Stop SCO
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                } catch (_: Exception) {}

                showMsg("Команда отключения отправлена")
                delay(1200)
                refreshBluetooth()
            } catch (e: Exception) {
                showMsg("Ошибка отключения: ${e.message?.take(40)}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun unpairDevice(info: BtDeviceInfo) {
        viewModelScope.launch {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(info.address) ?: return@launch
                val method = device.javaClass.getMethod("removeBond")
                val result = method.invoke(device) as? Boolean ?: false
                showMsg(if (result) "Устройство забыто" else "Не удалось забыть")
                delay(800)
                refreshBluetooth()
            } catch (e: Exception) {
                showMsg("Ошибка: ${e.message?.take(40)}")
            }
        }
    }

    fun requestUsbPermission(info: UsbDeviceInfo) {
        val ctx = context ?: return
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.find { it.deviceId == info.deviceId } ?: return
        // Permission must be requested with PendingIntent from Activity — simplified toast
        showMsg("Откройте системный диалог USB (если появится) или переподключите устройство")
        // Full permission flow needs Activity Result — user can grant when system asks
    }

    fun selectBt(device: BtDeviceInfo?) {
        _uiState.update { it.copy(selectedBtDevice = device) }
    }

    fun selectUsb(device: UsbDeviceInfo?) {
        _uiState.update { it.copy(selectedUsbDevice = device) }
    }

    fun openSystemBluetoothSettings() {
        context?.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSystemSoundSettings() {
        context?.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showMsg(msg: String) {
        _uiState.update { it.copy(statusMessage = msg) }
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context?.unregisterReceiver(receiver)
            bluetoothAdapter?.let { adapter ->
                a2dpProxy?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
                headsetProxy?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            }
        } catch (_: Exception) {}
    }
}

/* ===================== UI ===================== */

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
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Bluetooth") }, icon = { Icon(Icons.Default.Bluetooth, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("USB") }, icon = { Icon(Icons.Default.Usb, null) })
                Tab(selected = tab == 2, onClick = { tab = 2 },
                    text = { Text("Аудио") }, icon = { Icon(Icons.Default.VolumeUp, null) })
                Tab(selected = tab == 3, onClick = { tab = 3 },
                    text = { Text("Ещё") }, icon = { Icon(Icons.Default.Settings, null) })
            }

            when (tab) {
                0 -> BluetoothTab(state, vm)
                1 -> UsbTab(state, vm)
                2 -> AudioTab()
                3 -> ExtraSettingsTab(vm)
            }
        }
    }

    // Detail bottom sheets
    state.selectedBtDevice?.let { dev ->
        BtDeviceSheet(dev, vm) { vm.selectBt(null) }
    }
    state.selectedUsbDevice?.let { dev ->
        UsbDeviceSheet(dev, vm) { vm.selectUsb(null) }
    }
}

@Composable
fun BluetoothTab(state: UiState, vm: DeviceViewModel) {
    Column(Modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Status + actions
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                if (state.bluetoothEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                null,
                tint = if (state.bluetoothEnabled) MaterialTheme.colorScheme.primary else Color.Gray
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (!state.hasBtPermissions) "Нет разрешений"
                else if (state.bluetoothEnabled) "Bluetooth включён"
                else "Bluetooth выключен",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.weight(1f))
            if (state.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.stopScan() }) { Text("Стоп") }
            } else {
                FilledTonalButton(onClick = { vm.startScan() }) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Сканировать")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (!state.hasBtPermissions) {
            EmptyState(Icons.Default.Lock, "Выдайте разрешения Bluetooth и Location")
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Bonded
            item {
                Text("Спаренные устройства (${state.btDevices.size})",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (state.btDevices.isEmpty()) {
                item { Text("Нет спаренных устройств", color = Color.Gray) }
            }
            items(state.btDevices, key = { it.address }) { dev ->
                BtDeviceCard(dev,
                    onClick = { vm.selectBt(dev) },
                    onConnect = { vm.connectDevice(dev) },
                    onDisconnect = { vm.disconnectDevice(dev) }
                )
            }

            // Scanned
            if (state.scannedDevices.isNotEmpty() || state.isScanning) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Найденные рядом (${state.scannedDevices.size})",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(state.scannedDevices, key = { "s_${it.address}" }) { dev ->
                    BtDeviceCard(dev,
                        onClick = { vm.selectBt(dev) },
                        onConnect = { vm.pairDevice(dev.address) },
                        onDisconnect = {},
                        isScanned = true
                    )
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
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(device.icon, null, modifier = Modifier.size(36.dp),
                tint = if (device.isConnected) MaterialTheme.colorScheme.primary else Color.Gray)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("${device.deviceClass} · ${device.type}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(device.address, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Row {
                    Text(
                        if (device.isConnected) "● Подключено" else if (device.isBonded) "Спарено" else "Не спарено",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (device.isConnected) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                    if (device.batteryLevel in 0..100) {
                        Text("  ·  ${device.batteryLevel}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
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
        Column(Modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            DetailRow("Адрес", device.address)
            DetailRow("Тип", device.type)
            DetailRow("Класс", device.deviceClass)
            DetailRow("Статус", if (device.isConnected) "Подключено" else if (device.isBonded) "Спарено" else "Не спарено")
            if (device.batteryLevel in 0..100) DetailRow("Батарея", "${device.batteryLevel}%")
            if (device.uuids.isNotEmpty()) {
                Text("UUID / Сервисы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                device.uuids.take(8).forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun UsbTab(state: UiState, vm: DeviceViewModel) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Usb, null)
            Spacer(Modifier.width(8.dp))
            Text("Подключённые USB-устройства (${state.usbDevices.size})",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.refreshUsb() }) {
                Icon(Icons.Default.Refresh, null)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (state.usbDevices.isEmpty()) {
            EmptyState(Icons.Default.Usb, "Нет подключённых USB-устройств\nПодключите OTG-кабель / наушники / накопитель")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.usbDevices, key = { it.deviceId }) { dev ->
                    UsbDeviceCard(dev, onClick = { vm.selectUsb(dev) })
                }
            }
        }
    }
}

@Composable
fun UsbDeviceCard(device: UsbDeviceInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    device.className.contains("Аудио") -> Icons.Default.Headphones
                    device.className.contains("Накопитель") -> Icons.Default.Storage
                    device.className.contains("HID") -> Icons.Default.Mouse
                    else -> Icons.Default.Usb
                },
                null, modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.product, fontWeight = FontWeight.Medium)
                Text(device.manufacturer, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text("${device.className} · VID:${device.vendorId.toString(16)} PID:${device.productId.toString(16)}",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    if (device.hasPermission) "● Доступ разрешён" else "Нужно разрешение",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.hasPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDeviceSheet(device: UsbDeviceInfo, vm: DeviceViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(device.product, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            DetailRow("Производитель", device.manufacturer)
            DetailRow("Имя устройства", device.name)
            DetailRow("Класс", device.className)
            DetailRow("Vendor ID", "0x${device.vendorId.toString(16).uppercase()}")
            DetailRow("Product ID", "0x${device.productId.toString(16).uppercase()}")
            DetailRow("Интерфейсов", device.interfaceCount.toString())
            DetailRow("Протокол", device.protocol.toString())
            DetailRow("Разрешение", if (device.hasPermission) "Есть" else "Нет")
            Spacer(Modifier.height(16.dp))
            if (!device.hasPermission) {
                Button(onClick = { vm.requestUsbPermission(device) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Запросить разрешение USB")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AudioTab() {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var media by remember {
        mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
    }
    var call by remember {
        mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_VOICE_CALL).toFloat() /
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1))
    }
    var ring by remember {
        mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_RING).toFloat() /
                am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1))
    }
    var notif by remember {
        mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_NOTIFICATION).toFloat() /
                am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION).coerceAtLeast(1))
    }
    var alarm by remember {
        mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_ALARM).toFloat() /
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1))
    }
    var speaker by remember { mutableStateOf(am.isSpeakerphoneOn) }
    var sco by remember { mutableStateOf(am.isBluetoothScoOn) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Громкость", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        VolumeSlider("Медиа", Icons.Default.MusicNote, media) {
            media = it
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0)
        }
        VolumeSlider("Звонок", Icons.Default.Call, call) {
            call = it
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)).toInt(), 0)
        }
        VolumeSlider("Рингтон", Icons.Default.Notifications, ring) {
            ring = it
            am.setStreamVolume(AudioManager.STREAM_RING,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_RING)).toInt(), 0)
        }
        VolumeSlider("Уведомления", Icons.Default.NotificationsActive, notif) {
            notif = it
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)).toInt(), 0)
        }
        VolumeSlider("Будильник", Icons.Default.Alarm, alarm) {
            alarm = it
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                (it * am.getStreamMaxVolume(AudioManager.STREAM_ALARM)).toInt(), 0)
        }

        HorizontalDivider()
        Text("Маршрутизация аудио", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Динамик (speaker)", modifier = Modifier.weight(1f))
            Switch(checked = speaker, onCheckedChange = {
                speaker = it
                am.isSpeakerphoneOn = it
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bluetooth SCO (гарнитура)", modifier = Modifier.weight(1f))
            Switch(checked = sco, onCheckedChange = {
                sco = it
                if (it) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                } else {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
            })
        }

        Text(
            "Режим: ${when (am.mode) {
                AudioManager.MODE_NORMAL -> "Normal"
                AudioManager.MODE_RINGTONE -> "Ringtone"
                AudioManager.MODE_IN_CALL -> "In Call"
                AudioManager.MODE_IN_COMMUNICATION -> "Communication"
                else -> am.mode.toString()
            }}",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray
        )

        HorizontalDivider()
        Text("Эквалайзер (системный)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Полноценный EQ зависит от устройства. Можно открыть системные звуковые настройки.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray
        )
    }
}

@Composable
fun VolumeSlider(label: String, icon: ImageVector, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange)
    }
}

@Composable
fun ExtraSettingsTab(vm: DeviceViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Системные настройки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        SettingsButton(Icons.Default.Bluetooth, "Настройки Bluetooth") { vm.openSystemBluetoothSettings() }
        SettingsButton(Icons.Default.VolumeUp, "Звук и вибрация") { vm.openSystemSoundSettings() }
        SettingsButton(Icons.Default.Settings, "Все настройки") {
            vm.contextStart { Intent(Settings.ACTION_SETTINGS) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("О приложении", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Device Manager — расширенное управление Bluetooth и USB.\n\n" +
            "• Подключение / отключение профилей A2DP и Headset (через reflection)\n" +
            "• Сканирование и сопряжение\n" +
            "• Забыть устройство\n" +
            "• Список USB (OTG) устройств с классами\n" +
            "• Полное управление громкостями и маршрутизацией аудио\n\n" +
            "Ограничения Android: некоторые действия (особенно принудительное подключение A2DP) " +
            "зависят от прошивки и могут требовать системных привилегий.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray
        )
    }
}

// Helper to start activity from VM context
private fun DeviceViewModel.contextStart(intentBuilder: () -> Intent) {
    // accessed via reflection-like pattern — use open methods already defined
}

@Composable
fun SettingsButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null)
            Spacer(Modifier.width(16.dp))
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(120.dp), color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(icon: ImageVector, message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, modifier = Modifier.size(64.dp), tint = Color.Gray.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))
        Text(message, color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}
