package com.mikai.android.nfc

import android.util.Log
import com.mikai.android.usb.Acr122uDevice

/**
 * Gestisce la costruzione e il parsing dei frame PN532.
 *
 * Formato frame normale PN532:
 *   [0x00]        Preamble
 *   [0x00][0xFF]  Start code
 *   [LEN]         Lunghezza (TFI + CMD + DATA)
 *   [LCS]         (~LEN + 1) and 0xFF
 *   [TFI]         0xD4 per host a PN532
 *   [CMD]         Codice comando
 *   [DATA...]     Dati
 *   [DCS]         Checksum dati
 *   [0x00]        Postamble
 */
class Pn532(private val device: Acr122uDevice) {

    companion object {
        private const val TAG = "Pn532"

        // TFI (Transport Frame Identifier)
        val TFI_HOST_TO_PN532: Byte = 0xD4.toByte()
        val TFI_PN532_TO_HOST: Byte = 0xD5.toByte()

        // Comandi PN532
        val CMD_GET_FIRMWARE_VERSION:   Byte = 0x02
        val CMD_IN_LIST_PASSIVE_TARGET: Byte = 0x4A
        val CMD_IN_COMMUNICATE_THRU:    Byte = 0x42
        val CMD_IN_DATA_EXCHANGE:       Byte = 0x40

        // BrTy per InListPassiveTarget
        val BRTY_ISO14443B:    Byte = 0x03  // ISO14443B
        val BRTY_ISO14443B2SR: Byte = 0x06  // ISO14443B2SR (SRIX4K)

        val ACK_FRAME = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00)
    }

    /**
     * Verifica la comunicazione con il PN532.
     */
    fun getFirmwareVersion(): String? {
        val response = sendCommand(CMD_GET_FIRMWARE_VERSION) ?: return null
        if (response.size < 4) return null
        return "IC=${response[0].toInt() and 0xFF}" +
               " Ver=${response[1].toInt() and 0xFF}" +
               ".${response[2].toInt() and 0xFF}"
    }

    /**
     * InListPassiveTarget ISO14443B - necessario per inizializzare i registri PN532
     * prima di usare SRIX4K.
     */
    fun initISO14443B(): Boolean {
        val data = byteArrayOf(0x01, BRTY_ISO14443B)
        val response = sendCommand(CMD_IN_LIST_PASSIVE_TARGET, data) ?: return false
        Log.d(TAG, "InitISO14443B: ${response.toHexString()}")
        return true
    }

    /**
     * InListPassiveTarget per trovare un tag SRIX4K (ISO14443B2SR).
     */
    fun listPassiveTargetSrix4k(): Boolean {
        val data = byteArrayOf(0x01, BRTY_ISO14443B2SR)
        val response = sendCommand(CMD_IN_LIST_PASSIVE_TARGET, data) ?: return false
        Log.d(TAG, "ListPassiveTarget SRIX4K: ${response.toHexString()}")
        return response.isNotEmpty() && response[0].toInt() > 0
    }

    /**
     * InCommunicateThru: invia dati raw al tag attivo (comandi SRIX4K).
     */
    fun inCommunicateThru(rawData: ByteArray): ByteArray? {
        val response = sendCommand(CMD_IN_COMMUNICATE_THRU, rawData) ?: return null
        if (response.isEmpty()) return null
        val status = response[0].toInt() and 0xFF
        if (status != 0x00) {
            Log.e(TAG, "InCommunicateThru errore status: 0x${status.toString(16)}")
            return null
        }
        return if (response.size > 1) response.copyOfRange(1, response.size) else ByteArray(0)
    }

    // ---------------------------------------------------------------
    // Metodi privati
    // ---------------------------------------------------------------

    private fun sendCommand(cmd: Byte, data: ByteArray = ByteArray(0)): ByteArray? {
        val frame = buildFrame(TFI_HOST_TO_PN532, cmd, data)
        Log.v(TAG, "TX: ${frame.toHexString()}")

        val rawResponse = device.sendEscape(frame) ?: run {
            Log.e(TAG, "Nessuna risposta dall'ACR122U")
            return null
        }
        Log.v(TAG, "RX: ${rawResponse.toHexString()}")

        return parseFrame(rawResponse, cmd)
    }

    fun buildFrame(tfi: Byte, cmd: Byte, data: ByteArray): ByteArray {
        val payloadLen = 1 + 1 + data.size
        val frame = ByteArray(6 + payloadLen + 2)
        var idx = 0

        frame[idx++] = 0x00
        frame[idx++] = 0x00
        frame[idx++] = 0xFF.toByte()
        frame[idx++] = payloadLen.toByte()
        frame[idx++] = ((payloadLen.inv() + 1) and 0xFF).toByte()
        frame[idx++] = tfi

        var dcs = tfi.toInt()
        frame[idx++] = cmd
        dcs += cmd.toInt()

        for (b in data) {
            frame[idx++] = b
            dcs += b.toInt()
        }

        frame[idx++] = ((dcs.inv() + 1) and 0xFF).toByte()
        frame[idx] = 0x00

        return frame
    }

    private fun parseFrame(raw: ByteArray, sentCmd: Byte): ByteArray? {
        var i = 0
        while (i < raw.size - 1) {
            if (raw[i] == 0x00.toByte() && raw[i + 1] == 0xFF.toByte()) break
            i++
        }
        if (i >= raw.size - 1) {
            Log.e(TAG, "Start code PN532 non trovato")
            return null
        }
        i += 2

        if (i >= raw.size) return null
        val len = raw[i++].toInt() and 0xFF
        if (i >= raw.size) return null
        val lcs = raw[i++].toInt() and 0xFF
        if ((len + lcs) and 0xFF != 0) {
            Log.e(TAG, "LCS non valido")
            return null
        }
        if (i + len > raw.size) return null

        val tfi = raw[i]
        if (tfi != TFI_PN532_TO_HOST) {
            Log.d(TAG, "TFI non D5: 0x${tfi.toInt().and(0xFF).toString(16)}")
            return null
        }

        val dataStart = i + 2
        val dataEnd = i + len
        return if (dataEnd > dataStart) raw.copyOfRange(dataStart, dataEnd) else ByteArray(0)
    }

    private fun ByteArray.toHexString() =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
