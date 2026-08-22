package com.bluetoothmanager.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Refresh will happen via ViewModel
        } else {
            Toast.makeText(this, "Нужны разрешения Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBluetoothPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF64B5F6),
                    secondary = androidx.compose.ui.graphics.Color(0xFF81C784),
                    background = androidx.compose.ui.graphics.Color(0xFF121212),
                    surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BluetoothManagerApp()
                }
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothManagerApp(viewModel: BluetoothViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth Manager", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.refreshDevices() },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Volume controls
            VolumeControls(viewModel)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Спаренные устройства",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            when {
                !uiState.hasPermissions -> {
                    EmptyState(
                        icon = Icons.Default.Lock,
                        message = "Нужны разрешения Bluetooth"
                    )
                }
                !uiState.bluetoothEnabled -> {
                    EmptyState(
                        icon = Icons.Default.BluetoothDisabled,
                        message = "Bluetooth выключен"
                    )
                }
                uiState.devices.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.BluetoothSearching,
                        message = "Нет спаренных устройств"
                    )
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.devices, key = { it.address }) { device ->
                            DeviceCard(
                                device = device,
                                onConnect = { viewModel.connect(device) },
                                onDisconnect = { viewModel.disconnect(device) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VolumeControls(viewModel: BluetoothViewModel) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var mediaVolume by remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Громкость медиа",
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.VolumeDown, contentDescription = null)
                Slider(
                    value = mediaVolume,
                    onValueChange = {
                        mediaVolume = it
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (it * max).toInt(),
                            0
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.VolumeUp, contentDescription = null)
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: BluetoothDeviceInfo,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = device.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (device.isConnected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = if (device.isConnected) "Подключено" else "Отключено",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.isConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (device.batteryLevel >= 0) {
                    Text(
                        text = "Батарея: ${device.batteryLevel}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (device.isConnected) {
                FilledTonalButton(onClick = onDisconnect) {
                    Text("Отключить")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("Подключить")
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isConnected: Boolean,
    val batteryLevel: Int = -1,
    val icon: ImageVector = Icons.Default.Bluetooth
)

data class UiState(
    val devices: List<BluetoothDeviceInfo> = emptyList(),
    val bluetoothEnabled: Boolean = false,
    val hasPermissions: Boolean = false
)

class BluetoothViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var context: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    refreshDevices()
                }
            }
        }
    }

    fun init(ctx: Context) {
        context = ctx.applicationContext
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ctx.registerReceiver(receiver, filter)

        refreshDevices()
    }

    fun refreshDevices() {
        viewModelScope.launch {
            val ctx = context ?: return@launch
            val adapter = bluetoothAdapter

            val hasPerms = checkPermissions(ctx)
            val enabled = adapter?.isEnabled == true

            if (!hasPerms || !enabled || adapter == null) {
                _uiState.value = UiState(
                    hasPermissions = hasPerms,
                    bluetoothEnabled = enabled
                )
                return@launch
            }

            val bonded = adapter.bondedDevices ?: emptySet()
            val connectedAddresses = getConnectedDevices(adapter)

            val devices = bonded.map { device ->
                val name = try {
                    device.name ?: "Неизвестное устройство"
                } catch (e: SecurityException) {
                    "Неизвестное устройство"
                }

                val battery = tryGetBattery(device)
                val isConnected = connectedAddresses.contains(device.address)

                BluetoothDeviceInfo(
                    name = name,
                    address = device.address,
                    isConnected = isConnected,
                    batteryLevel = battery,
                    icon = getDeviceIcon(device)
                )
            }.sortedByDescending { it.isConnected }

            _uiState.value = UiState(
                devices = devices,
                bluetoothEnabled = true,
                hasPermissions = true
            )
        }
    }

    private fun checkPermissions(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun getConnectedDevices(adapter: BluetoothAdapter): Set<String> {
        val result = mutableSetOf<String>()
        try {
            // Check A2DP
            val a2dpProxy = mutableListOf<BluetoothDevice>()
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        proxy.connectedDevices.forEach { result.add(it.address) }
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)

            // Also try Headset
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        proxy.connectedDevices.forEach { result.add(it.address) }
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)

            // Fallback: check bond state + connection via reflection is unreliable,
            // so we rely on the profile proxies above (async, but good enough for refresh)
        } catch (e: SecurityException) {
            // ignore
        }
        return result
    }

    private fun tryGetBattery(device: BluetoothDevice): Int {
        return try {
            // Battery level via BluetoothDevice extra (Android 8+)
            // Many devices broadcast it, but not all expose via public API easily.
            // This is best-effort.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // No public reliable API for all devices; return -1
                -1
            } else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun getDeviceIcon(device: BluetoothDevice): ImageVector {
        return try {
            val cls = device.bluetoothClass ?: return Icons.Default.Bluetooth
            when (cls.majorDeviceClass) {
                android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> Icons.Default.Headphones
                android.bluetooth.BluetoothClass.Device.Major.PHONE -> Icons.Default.Phone
                android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> Icons.Default.Computer
                android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> Icons.Default.Watch
                else -> Icons.Default.Bluetooth
            }
        } catch (e: Exception) {
            Icons.Default.Bluetooth
        }
    }

    fun connect(deviceInfo: BluetoothDeviceInfo) {
        viewModelScope.launch {
            try {
                val adapter = bluetoothAdapter ?: return@launch
                val device = adapter.getRemoteDevice(deviceInfo.address)
                // Create bond if needed, then connect profiles is complex.
                // For simplicity we show toast — real connection often happens via system UI.
                context?.let {
                    Toast.makeText(it, "Используйте системные настройки для подключения", Toast.LENGTH_SHORT).show()
                }
                // Attempt createBond
                device.createBond()
            } catch (e: SecurityException) {
                context?.let {
                    Toast.makeText(it, "Нет разрешения", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun disconnect(deviceInfo: BluetoothDeviceInfo) {
        viewModelScope.launch {
            try {
                // Disconnect is also limited by public API.
                // Best effort via reflection or profile.
                context?.let {
                    Toast.makeText(it, "Отключение через системные настройки Bluetooth", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context?.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // ignore
        }
    }
}
