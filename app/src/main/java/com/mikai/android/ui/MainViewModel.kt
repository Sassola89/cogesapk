package com.mikai.android.ui

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mikai.android.mykey.MyKeyManager
import com.mikai.android.nfc.Pn532
import com.mikai.android.nfc.Srix4kReader
import com.mikai.android.usb.Acr122uDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val readerConnected: Boolean = false,
    val cardRead: Boolean = false,
    val creditCents: Int = -1,
    val uid: String = "",
    val isLoading: Boolean = false,
    val statusMessage: String = "Collega l'ACR122U via USB OTG",
    val logLines: List<String> = emptyList(),
    val errorMessage: String? = null
)

class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_LOG_LINES = 50
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var acr122u: Acr122uDevice? = null
    private var srix4kReader: Srix4kReader? = null
    private var myKeyManager: MyKeyManager? = null

    // ────────────────────────────────────────────────────────────────────
    // Connessione lettore
    // ────────────────────────────────────────────────────────────────────

    /**
     * Connette l'ACR122U quando viene rilevato via USB OTG.
     */
    fun connectReader(usbDevice: UsbDevice, usbManager: UsbManager) {
        viewModelScope.launch {
            val device = Acr122uDevice(usbDevice, usbManager)
            val connected = withContext(Dispatchers.IO) { device.open() }

            if (connected) {
                acr122u = device
                log("✅ ACR122U connesso: ${usbDevice.productName ?: usbDevice.deviceName}")
                updateState {
                    copy(
                        readerConnected = true,
                        statusMessage = "ACR122U collegato — avvicina la MyKey"
                    )
                }
            } else {
                log("❌ Impossibile aprire l'ACR122U")
                updateState { copy(statusMessage = "Errore connessione ACR122U") }
            }
        }
    }

    /**
     * Disconnette il lettore (es. USB scollegato).
     */
    fun disconnectReader() {
        acr122u?.close()
        acr122u = null
        srix4kReader = null
        myKeyManager = null
        updateState {
            copy(
                readerConnected = false,
                cardRead = false,
                creditCents = -1,
                uid = "",
                statusMessage = "Lettore disconnesso. Ricollega l'ACR122U.",
                errorMessage = null
            )
        }
        log("🔌 ACR122U disconnesso")
    }

    /**
     * Controlla se un UsbDevice è un ACR122U.
     */
    fun isAcr122u(device: UsbDevice): Boolean =
        device.vendorId == Acr122uDevice.VENDOR_ID &&
        (device.productId == Acr122uDevice.PRODUCT_ID ||
         device.productId == Acr122uDevice.PRODUCT_ID_ALT)

    // ────────────────────────────────────────────────────────────────────
    // Operazioni MyKey
    // ────────────────────────────────────────────────────────────────────

    /**
     * Legge la MyKey: UID, tutti i blocchi EEPROM, calcola credito.
     */
    fun readCard() {
        val device = acr122u ?: run {
            updateState { copy(errorMessage = "Nessun lettore connesso") }
            return
        }

        viewModelScope.launch {
            updateState { copy(isLoading = true, statusMessage = "⏳ Lettura carta in corso…", errorMessage = null) }
            log("📖 Avvio lettura MyKey…")

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pn532  = Pn532(device)
                    val reader = Srix4kReader(pn532)

                    // Verifica firmware PN532
                    val fw = pn532.getFirmwareVersion()
                    if (fw != null) Log.d(TAG, "PN532 firmware: $fw")

                    if (!reader.initialize()) {
                        throw Exception("Nessuna MyKey rilevata. Avvicina la carta al lettore.")
                    }

                    val manager = MyKeyManager(reader)
                    manager.recalculateKey()

                    srix4kReader = reader
                    myKeyManager = manager

                    Triple(manager.getCurrentCreditCents(), reader.getUidString(), manager)
                }
            }

            result.onSuccess { (cents, uidStr, manager) ->
                log("✅ Carta letta. UID: $uidStr")
                log("💰 Credito: ${formatCents(cents)}")

                if (manager.isReset()) log("⚠️ Carta resettata (nessun vendor associato)")
                if (manager.hasLockId()) log("⚠️ Lock ID rilevato — ricarica potrebbe non funzionare")

                updateState {
                    copy(
                        cardRead = true,
                        creditCents = cents,
                        uid = uidStr,
                        isLoading = false,
                        statusMessage = "Carta letta",
                        errorMessage = null
                    )
                }
}.onFailure { e ->
    log("❌ Errore: ${e.message}")
    log("❌ Stack: ${e.stackTraceToString().take(300)}")
                updateState {
                    copy(
                        isLoading = false,
                        statusMessage = "Errore lettura",
                        errorMessage = e.message,
                        cardRead = false
                    )
                }
            }
        }
    }

    /**
     * Aggiunge credito alla MyKey e scrive le modifiche sul tag.
     *
     * @param eurosString importo in euro come stringa (es. "2.50")
     */
    fun addCredit(eurosString: String) {
        val cents = parseToCents(eurosString) ?: run {
            updateState { copy(errorMessage = "Importo non valido: usa il formato 1.50") }
            return
        }
        if (cents <= 0) {
            updateState { copy(errorMessage = "L'importo deve essere maggiore di 0") }
            return
        }

        performCreditOperation("Aggiunta ${formatCents(cents)}") {
            myKeyManager!!.addCents(cents).getOrThrow()
        }
    }

    /**
     * Imposta il credito della MyKey (azzera lo storico e imposta il nuovo valore).
     *
     * @param eurosString importo in euro come stringa
     */
    fun setCredit(eurosString: String) {
        val cents = parseToCents(eurosString) ?: run {
            updateState { copy(errorMessage = "Importo non valido") }
            return
        }

        performCreditOperation("Impostazione a ${formatCents(cents)}") {
            myKeyManager!!.setCents(cents).getOrThrow()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Metodi privati
    // ────────────────────────────────────────────────────────────────────

    private fun performCreditOperation(description: String, operation: suspend () -> Unit) {
        val reader  = srix4kReader  ?: run { updateState { copy(errorMessage = "Leggi prima la carta") }; return }
        val manager = myKeyManager  ?: run { updateState { copy(errorMessage = "Leggi prima la carta") }; return }

        viewModelScope.launch {
            updateState {
                copy(isLoading = true, statusMessage = "⏳ $description…", errorMessage = null)
            }
            log("✏️ $description")

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    operation()
                    // Scrivi i blocchi modificati sul tag fisico
                    if (!reader.writeAllModified()) {
                        throw Exception("Errore scrittura sulla carta. Riprova.")
                    }
                    manager.getCurrentCreditCents()
                }
            }

            result.onSuccess { newCents ->
                log("✅ Operazione completata. Nuovo credito: ${formatCents(newCents)}")
                updateState {
                    copy(
                        creditCents = newCents,
                        isLoading = false,
                        statusMessage = "Operazione completata",
                        errorMessage = null
                    )
                }
            }.onFailure { e ->
                log("❌ Errore: ${e.message}")
                updateState {
                    copy(
                        isLoading = false,
                        statusMessage = "Errore operazione",
                        errorMessage = e.message
                    )
                }
            }
        }
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
        val line = "[$timestamp] $message"
        Log.d(TAG, message)
        _uiState.value = _uiState.value.let { s ->
            val lines = (s.logLines + line).takeLast(MAX_LOG_LINES)
            s.copy(logLines = lines)
        }
    }

    private fun updateState(update: UiState.() -> UiState) {
        _uiState.value = _uiState.value.update()
    }

    private fun parseToCents(input: String): Int? {
        val normalized = input.trim().replace(",", ".")
        val euros = normalized.toDoubleOrNull() ?: return null
        return (euros * 100).toInt()
    }

    private fun formatCents(cents: Int): String {
        if (cents < 0) return "—"
        return "%.2f€".format(cents / 100.0)
    }

    override fun onCleared() {
        super.onCleared()
        acr122u?.close()
    }
}
