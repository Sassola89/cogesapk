package com.mikai.android.usb

import android.hardware.usb.*
import android.util.Log

/**
 * Driver per comunicare con l'ACR122U tramite USB Host API (OTG).
 *
 * L'ACR122U espone due interfacce USB:
 *   - Interfaccia 0: CCID (class 0x0B) con endpoint Bulk IN/OUT
 *   - Interfaccia 1: HID (class 0x03)
 *
 * Usiamo l'interfaccia CCID con i comandi PC_to_RDR_Escape (0x6B)
 * per inviare frame PN532 direttamente al chip interno.
 */
class Acr122uDevice(private val usbDevice: UsbDevice, private val usbManager: UsbManager) {

    companion object {
        private const val TAG = "Acr122uDevice"

        // ACR122U USB identifiers
        const val VENDOR_ID  = 0x072F
        const val PRODUCT_ID = 0x2200
        const val PRODUCT_ID_ALT = 0x2214

        // CCID message types
        private const val PC_TO_RDR_ESCAPE        = 0x6B.toByte()
        private const val PC_TO_RDR_ICC_POWER_ON  = 0x62.toByte()
        private const val PC_TO_RDR_ICC_POWER_OFF = 0x63.toByte()
        private const val RDR_TO_PC_ESCAPE        = 0x83.toByte()
        private const val RDR_TO_PC_DATA_BLOCK    = 0x80.toByte()

        // CCID header length
        private const val CCID_HEADER_LEN = 10

        // USB timeout ms
        private const val USB_TIMEOUT_MS = 3000
    }

    private var usbInterface: UsbInterface? = null
    private var endpointIn:  UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var connection:  UsbDeviceConnection? = null
    private var seqNumber: Byte = 0

    val isConnected: Boolean get() = connection != null

    /**
     * Apre la connessione USB al lettore.
     * @return true se la connessione è riuscita
     */
fun open(): Boolean {
    val ccidInterface = findCcidInterface() ?: run {
        Log.e(TAG, "Interfaccia CCID non trovata")
        return false
    }

    val conn = usbManager.openDevice(usbDevice) ?: run {
        Log.e(TAG, "Impossibile aprire il dispositivo USB")
        return false
    }

    if (!conn.claimInterface(ccidInterface, true)) {
        Log.e(TAG, "Impossibile reclamare l'interfaccia CCID")
        conn.close()
        return false
    }

    val (epIn, epOut) = findBulkEndpoints(ccidInterface) ?: run {
        Log.e(TAG, "Endpoint Bulk non trovati")
        conn.releaseInterface(ccidInterface)
        conn.close()
        return false
    }

    usbInterface = ccidInterface
    endpointIn  = epIn
    endpointOut = epOut
    connection  = conn
    seqNumber   = 0

Log.d(TAG, "ACR122U connesso: ${usbDevice.deviceName}")

Thread.sleep(100)
powerOn()
Thread.sleep(100)

// SAMConfiguration - OBBLIGATORIO per il PN532
// Senza questo il chip non risponde ai comandi
samConfiguration()
Thread.sleep(100)

return true
}
private fun samConfiguration(): Boolean {
    // Comando PN532: SAMConfiguration - modalità normale, no timeout, no IRQ
    val samCmd = byteArrayOf(
        0x00, 0x00, 0xFF.toByte(), // preamble + start
        0x05,                       // LEN = 5
        0xFB.toByte(),              // LCS
        0xD4.toByte(),              // TFI host->PN532
        0x14,                       // CMD: SAMConfiguration
        0x01,                       // Mode: Normal
        0x14,                       // Timeout: 20 * 50ms = 1s
        0x01,                       // IRQ: yes
        0x02.toByte(),              // DCS
        0x00                        // postamble
    )
    val result = sendEscape(samCmd)
    Log.d(TAG, "SAMConfiguration: ${result?.toHexString() ?: "null"}")
    return result != null
}
    fun close() {
        usbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection  = null
        usbInterface = null
        endpointIn  = null
        endpointOut = null
    }

    /**
     * Invia un frame PN532 tramite CCID Escape e restituisce la risposta del PN532.
     *
     * @param pn532Frame frame PN532 completo (preamble + start codes + LEN + TFI + CMD + DATA + DCS + postamble)
     * @return array di byte della risposta PN532, o null in caso di errore
     */
fun sendEscape(pn532Frame: ByteArray): ByteArray? {
    val conn = connection ?: return null
    val epOut = endpointOut ?: return null
    val epIn  = endpointIn  ?: return null

    val seq = seqNumber++
    val ccidMsg = buildCcidEscape(pn532Frame, seq)

    Log.d(TAG, "CCID TX: ${ccidMsg.toHexString()}")

val bytesSent = conn.bulkTransfer(epOut, ccidMsg, ccidMsg.size, USB_TIMEOUT_MS)
if (bytesSent < 0) {
    Log.e(TAG, "Errore bulkTransfer OUT: $bytesSent")
    return null
}

Thread.sleep(20) // piccolo delay per dare tempo al chip di rispondere

val responseBuffer = ByteArray(512)
    val bytesReceived = conn.bulkTransfer(epIn, responseBuffer, responseBuffer.size, USB_TIMEOUT_MS)
    Log.d(TAG, "CCID RX ($bytesReceived bytes): ${responseBuffer.take(bytesReceived.coerceAtLeast(0)).toByteArray().toHexString()}")

    if (bytesReceived < CCID_HEADER_LEN) {
        Log.e(TAG, "Risposta troppo corta: $bytesReceived")
        return null
    }

    Log.d(TAG, "CCID header[0]=${responseBuffer[0].toInt().and(0xFF).toString(16)} status=${responseBuffer[7].toInt().and(0xFF).toString(16)}")

    if (responseBuffer[0] != RDR_TO_PC_ESCAPE) {
        Log.e(TAG, "Tipo risposta inatteso: 0x${responseBuffer[0].toInt().and(0xFF).toString(16)}")
        // Prova comunque a restituire il payload
        return responseBuffer.copyOfRange(CCID_HEADER_LEN, bytesReceived)
    }

    val payloadLen = (responseBuffer[1].toInt() and 0xFF) or
            ((responseBuffer[2].toInt() and 0xFF) shl 8) or
            ((responseBuffer[3].toInt() and 0xFF) shl 16) or
            ((responseBuffer[4].toInt() and 0xFF) shl 24)

    Log.d(TAG, "Payload len: $payloadLen")

    if (payloadLen <= 0 || CCID_HEADER_LEN + payloadLen > bytesReceived) {
        return responseBuffer.copyOfRange(CCID_HEADER_LEN, bytesReceived)
    }

    return responseBuffer.copyOfRange(CCID_HEADER_LEN, CCID_HEADER_LEN + payloadLen)
}

    /**
     * Alimenta il chip card slot (ICC Power On).
     */
    fun powerOn(): Boolean {
        val conn = connection ?: return false
        val epOut = endpointOut ?: return false
        val epIn  = endpointIn  ?: return false
        val seq = seqNumber++

        // PC_to_RDR_IccPowerOn
        val msg = ByteArray(10)
        msg[0] = PC_TO_RDR_ICC_POWER_ON
        // dwLength = 0
        // bSlot = 0
        msg[6] = seq
        // bPowerSelect = 0 (automatico)

        conn.bulkTransfer(epOut, msg, msg.size, USB_TIMEOUT_MS)

        val resp = ByteArray(272)
        val len = conn.bulkTransfer(epIn, resp, resp.size, USB_TIMEOUT_MS)
        return len >= CCID_HEADER_LEN && (resp[7].toInt() and 0xC0) == 0
    }

    // ────────────────────────────────────────────────────────────────────
    // Metodi privati
    // ────────────────────────────────────────────────────────────────────

private fun findCcidInterface(): UsbInterface? {
    // Prima cerca l'interfaccia CCID (class 0x0B)
    for (i in 0 until usbDevice.interfaceCount) {
        val iface = usbDevice.getInterface(i)
        if (iface.interfaceClass == 0x0B) {
            Log.d(TAG, "Trovata interfaccia CCID all'indice $i")
            return iface
        }
    }
    // Fallback: prova la prima interfaccia con endpoint Bulk
    for (i in 0 until usbDevice.interfaceCount) {
        val iface = usbDevice.getInterface(i)
        for (j in 0 until iface.endpointCount) {
            if (iface.getEndpoint(j).type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                Log.d(TAG, "Fallback: uso interfaccia $i (class=${iface.interfaceClass})")
                return iface
            }
        }
    }
    return null
}

    private fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var epIn: UsbEndpoint?  = null
        var epOut: UsbEndpoint? = null

        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN)  epIn  = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
            }
        }

        return if (epIn != null && epOut != null) Pair(epIn, epOut) else null
    }

    /**
     * Costruisce un messaggio CCID PC_to_RDR_Escape.
     *
     * Struttura (10 byte header + data):
     *   [0]    bMessageType  = 0x6B
     *   [1-4]  dwLength      = lunghezza data (LE)
     *   [5]    bSlot         = 0x00
     *   [6]    bSeq          = numero sequenza
     *   [7]    bRFU          = 0x00
     *   [8]    bRFU          = 0x00
     *   [9]    bRFU          = 0x00
     *   [10+]  abData        = frame PN532
     */
    private fun buildCcidEscape(data: ByteArray, seq: Byte): ByteArray {
        val msg = ByteArray(CCID_HEADER_LEN + data.size)
        msg[0] = PC_TO_RDR_ESCAPE
        msg[1] = (data.size and 0xFF).toByte()
        msg[2] = ((data.size shr 8) and 0xFF).toByte()
        msg[3] = ((data.size shr 16) and 0xFF).toByte()
        msg[4] = ((data.size shr 24) and 0xFF).toByte()
        msg[5] = 0x00  // bSlot
        msg[6] = seq
        msg[7] = 0x00  // bRFU
        msg[8] = 0x00  // bRFU
        msg[9] = 0x00  // bRFU
        data.copyInto(msg, CCID_HEADER_LEN)
        return msg
    }
    private fun ByteArray.toHexString() = joinToString(" ") { 
        "%02X".format(it.toInt() and 0xFF) 
    }

}
