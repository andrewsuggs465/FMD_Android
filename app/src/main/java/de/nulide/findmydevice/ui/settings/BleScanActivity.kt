package de.nulide.findmydevice.ui.settings

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import de.nulide.findmydevice.R
import de.nulide.findmydevice.databinding.ActivityBleScanBinding
import de.nulide.findmydevice.securepouch.DiscoveredDevice
import de.nulide.findmydevice.securepouch.DiscoveredDeviceState
import de.nulide.findmydevice.services.BleTrackerScanService
import de.nulide.findmydevice.ui.FmdActivity
import de.nulide.findmydevice.ui.UiUtil.Companion.setupEdgeToEdgeAppBar
import de.nulide.findmydevice.ui.UiUtil.Companion.setupEdgeToEdgeScrollView
import kotlinx.coroutines.launch

class BleScanActivity : FmdActivity() {

    private lateinit var binding: ActivityBleScanBinding
    private val viewModel: BleScanViewModel by viewModels()
    private lateinit var adapter: DiscoveredDeviceAdapter

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startScanIfReady()
        } else {
            Snackbar.make(binding.root, R.string.sp_scan_bt_off, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBleScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeAppBar(binding.appBar)
        setupEdgeToEdgeScrollView(binding.scrollView)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = DiscoveredDeviceAdapter(onDeviceTapped = ::onDeviceTapped)

        binding.recyclerDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerDevices.adapter = adapter

        binding.fabScan.setOnClickListener { startScanIfReady() }

        observeViewModel()

        // Stop background scan service while this screen is open to avoid scan conflict
        BleTrackerScanService.stop(this)

        startScanIfReady()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopScan()
        // Restart background service if any pouches are registered
        if (viewModel.bleRepo.hasPouches()) {
            BleTrackerScanService.start(this)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.scanProgress.isVisible = scanning
                        binding.fabScan.text = if (scanning) getString(R.string.sp_scan_scanning)
                                               else getString(R.string.sp_scan_button)
                        binding.fabScan.isEnabled = !scanning
                    }
                }
                launch {
                    viewModel.devices.collect { map ->
                        val list = map.values.sortedByDescending { it.rssi }
                        adapter.submitList(list)
                        binding.tvEmpty.isVisible = list.isEmpty() && !viewModel.isScanning.value
                    }
                }
                launch {
                    viewModel.error.collect { err ->
                        if (!err.isNullOrEmpty()) {
                            Snackbar.make(binding.root, err, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun startScanIfReady() {
        val btAdapter = (getSystemService(BluetoothManager::class.java))?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Snackbar.make(binding.root, R.string.sp_scan_bt_off, Snackbar.LENGTH_LONG).show()
            return
        }
        if (!hasRequiredPermissions()) {
            requestPermissions.launch(requiredPermissions())
            return
        }
        viewModel.startScan()
    }

    private fun onDeviceTapped(device: DiscoveredDevice) {
        when (device.state) {
            DiscoveredDeviceState.FOUND -> {
                viewModel.connectAndReadDeviceId(
                    address = device.address,
                    onResult = { deviceId, alreadyPaired ->
                        if (!alreadyPaired) showRegisterDialog(device.address, deviceId)
                    },
                    onFail = {
                        Snackbar.make(binding.root, R.string.sp_scan_error_tap_retry, Snackbar.LENGTH_SHORT).show()
                    },
                )
            }
            DiscoveredDeviceState.READY -> {
                val deviceId = device.deviceId ?: return
                showRegisterDialog(device.address, deviceId)
            }
            DiscoveredDeviceState.ERROR -> {
                viewModel.resetDeviceState(device.address)
            }
            else -> { /* no-op for other states */ }
        }
    }

    private fun showRegisterDialog(address: String, deviceId: String) {
        val view = layoutInflater.inflate(R.layout.dialog_register_device, null)
        val etDeviceId = view.findViewById<TextInputEditText>(R.id.editDeviceId)
        val etPassword = view.findViewById<TextInputEditText>(R.id.editPassword)
        val etToken = view.findViewById<TextInputEditText>(R.id.editToken)
        val tvError = view.findViewById<TextView>(R.id.tvRegisterError)

        etDeviceId.setText(deviceId)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sp_scan_register_title, deviceId))
            .setView(view)
            .setPositiveButton(R.string.sp_scan_register_button) { dialog, _ ->
                val pw = etPassword.text?.toString() ?: ""
                val token = etToken.text?.toString()?.trim() ?: ""
                if (pw.isEmpty() || token.isEmpty()) {
                    tvError.text = getString(R.string.sp_pair_error_empty)
                    tvError.visibility = View.VISIBLE
                    return@setPositiveButton
                }
                dialog.dismiss()
                viewModel.registerPouch(
                    address = address,
                    deviceId = deviceId,
                    password = pw,
                    token = token,
                    onSuccess = {
                        BleTrackerScanService.start(this)
                        Snackbar.make(binding.root, R.string.sp_pair_success, Snackbar.LENGTH_SHORT).show()
                    },
                    onError = { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    },
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.resetDeviceState(address)
            }
            .setOnCancelListener {
                viewModel.resetDeviceState(address)
            }
            .show()
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
