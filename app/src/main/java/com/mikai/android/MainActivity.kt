package com.mikai.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mikai.android.databinding.ActivityMainBinding
import com.mikai.android.ui.MainViewModel
import com.mikai.android.ui.UiState
import com.mikai.android.usb.Acr122uDevice
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.mikai.android.USB_PERMISSION"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager

    // BroadcastReceiver per eventi USB (connessione/disconnessione/permesso)
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        viewModel.connectReader(device, usbManager)
                    } else {
                        Toast.makeText(context, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    device?.let { handleUsbDeviceAttached(it) }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    viewModel.disconnectReader()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setupButtons()
        setupChips()
        observeState()
        registerUsbReceiver()

        // Controlla se l'app è stata avviata direttamente dalla connessione USB
        handleUsbIntent(intent)
        
        // Controlla se c'è già un ACR122U connesso
        checkAlreadyConnectedDevices()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    // ────────────────────────────────────────────────────────────────────
    // Setup UI
    // ────────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnRead.setOnClickListener {
            viewModel.readCard()
        }

        binding.btnAdd.setOnClickListener {
            val amount = binding.etAmount.text?.toString() ?: ""
            if (amount.isBlank()) {
                binding.etAmount.error = getString(R.string.invalid_amount)
                return@setOnClickListener
            }
            viewModel.addCredit(amount)
        }

        binding.btnSet.setOnClickListener {
            val amount = binding.etAmount.text?.toString() ?: ""
            if (amount.isBlank()) {
                binding.etAmount.error = getString(R.string.invalid_amount)
                return@setOnClickListener
            }
            // Chiedi conferma prima di impostare
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Conferma")
                .setMessage("Impostare il credito a €$amount?\nQuesta operazione azzera lo storico transazioni.")
                .setPositiveButton("Conferma") { _, _ -> viewModel.setCredit(amount) }
                .setNegativeButton("Annulla", null)
                .show()
        }
    }

    private fun setupChips() {
        val chips = mapOf(
            binding.chip050  to "0.50",
            binding.chip100  to "1.00",
            binding.chip200  to "2.00",
            binding.chip500  to "5.00",
            binding.chip1000 to "10.00"
        )
        chips.forEach { (chip, value) ->
            chip.setOnClickListener {
                binding.etAmount.setText(value)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Osservazione stato
    // ────────────────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: UiState) {
        // Status bar
        binding.tvStatus.text = when {
            state.isLoading          -> "⏳ ${state.statusMessage}"
            state.readerConnected    -> "✅ ${state.statusMessage}"
            else                     -> "🔌 ${state.statusMessage}"
        }
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    state.errorMessage != null -> R.color.red_error
                    state.readerConnected      -> R.color.green_success
                    else                       -> android.R.color.darker_gray
                }
            )
        )

        // Credito
        if (state.creditCents >= 0) {
            binding.tvCredit.text = "%.2f€".format(state.creditCents / 100.0)
            binding.tvCredit.setTextColor(
                ContextCompat.getColor(this,
                    if (state.creditCents < 50) R.color.red_error else R.color.green_success)
            )
        } else {
            binding.tvCredit.text = "—"
        }

        // UID
        binding.tvUid.text = if (state.uid.isNotEmpty()) "UID: ${state.uid}" else ""
        binding.tvUid.visibility = if (state.uid.isNotEmpty()) View.VISIBLE else View.GONE

        // Pulsanti
        binding.btnRead.isEnabled = state.readerConnected && !state.isLoading
        binding.btnAdd.isEnabled  = state.cardRead && !state.isLoading
        binding.btnSet.isEnabled  = state.cardRead && !state.isLoading

        // Log
        binding.tvLog.text = state.logLines.joinToString("\n")

        // Errore
        if (state.errorMessage != null) {
            Toast.makeText(this, state.errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Gestione USB
    // ────────────────────────────────────────────────────────────────────

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            device?.let { handleUsbDeviceAttached(it) }
        }
    }

    private fun handleUsbDeviceAttached(device: UsbDevice) {
        if (!viewModel.isAcr122u(device)) return

        if (usbManager.hasPermission(device)) {
            viewModel.connectReader(device, usbManager)
        } else {
            requestUsbPermission(device)
        }
    }

    private fun checkAlreadyConnectedDevices() {
        usbManager.deviceList.values
            .filter { viewModel.isAcr122u(it) }
            .forEach { handleUsbDeviceAttached(it) }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }
}
