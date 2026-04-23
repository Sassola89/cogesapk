package com.mikai.android.nfc

import android.util.Log
import com.mikai.android.usb.Acr122uDevice

/**
 * Gestisce la costruzione e il parsing dei frame PN532.
 *
 * Formato frame normale PN532:
 *   [0x00]              Preamble
 *   [0x00][0xFF]        Start code
 *   [LEN]               Lunghezza (TFI + CMD + DATA)
 *   [LCS]               (~LEN + 1) & 0xFF
 *   [TFI]               0xD4 per host→PN532
 *   [CMD]               Codice comando
 *   [DATA...]           Dati
 *   [DCS]               Checksum dati: (~(TFI+CMD+DATA[0]+...)+1) & 0xFF
 *   [0x00]              Postamble
 *
 * Frame ACK:
 *   [0x00][0x00][0xFF][0x00][0xFF][0x00]
 */
class Pn532(private val device: Acr122uDevice) {

    companion object {
        private const val TAG = "Pn532"

        // TFI (Transport Frame Identifier)
        const val TFI_HOST_TO_PN532: Byte = 0xD4.toByte()
        const val TFI_PN532_TO_HOST: Byte = 0xD5.toByte()

        // Comandi PN532
        const val CMD_GET_FIRMWARE_VERSION:      Byte = 0x02
        const val CMD_IN_LIST_PASSIVE_TARGET:    Byte = 0x4A
        const val CMD_IN_COMMUNICATE_THRU:       Byte = 0x42
        const val CMD_IN_DATA_EXCHANGE:          Byte = 0x40

        // BrTy (tipo modulazione) per InListPassiveTarget
        const val BRTY_ISO14443B:   Byte = 0x03  // ISO14443B
        const val BRTY_ISO14443B2SR: Byte = 0x06 // ISO14443B2SR (SRIX4K)
        const val BRTY_ISO14443B2CT: Byte = 0x07 // ST SR/CT (alternativo)

        val ACK_FRAME = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00)
    }

    /**
     * Verifica la comunicazione con il PN532.
     * @return versione firmware o null
     */
    fun getFirmwareVersion(): String? {
        val response = sendCommand(CMD_GET_FIRMWARE_VERSION) ?: return null
        if (response.size < 4) return null
        return "IC=${response[0].toInt().and(0xFF)}" +
               " Ver=${response[1].toInt().and(0xFF)}" +
               ".${response[2].toInt().and(0xFF)}" +
               " Support=${response[3].toInt().and(0xFF).toString(16)}"
    }

    /**
     * Esegue InListPassiveTarget per trovare un tag ISO14443B.
     * Necessario per inizializzare i registri interni del PN532 prima
     * di usare SRIX4K (ISO14443B2SR).
     * Vedi: https://github.com/nfc-tools/libnfc/issues/436
     */
    fun initISO14443B(): Boolean {
        val data = byteArrayOf(0x01, BRTY_ISO14443B)
        val response = sendCommand(CMD_IN_LIST_PASSIVE_TARGET, data) ?: return false
        // Non ci interessa il risultato, solo che il comando sia passato
        Log.d(TAG, "InitISO14443B risposta: ${response.toHexString()}")
        return true
    }

    /**
     * Esegue InListPassiveTarget per trovare un tag SRIX4K (ISO14443B2SR).
     * @return true se un tag SRIX4K è stato trovato
     */
    fun listPassiveTargetSrix4k(): Boolean {
        val data = byteArrayOf(0x01, BRTY_ISO14443B2SR)
        val response = sendCommand(CMD_IN_LIST_PASSIVE_TARGET, data) ?: return false
        Log.d(TAG, "ListPassiveTarget SRIX4K: ${response.toHexString()}")
        // response[0] = NbTg (numero tag trovati)
        return response.isNotEmpty() && response[0].toInt() > 0
    }

    /**
     * Esegue InCommunicateThru: manda dati raw al tag attivo.
     * Usato per i comandi SRIX4K (GET_UID, READ_BLOCK, WRITE_BLOCK).
     *
     * @param rawData dati da inviare al tag
     * @return risposta del tag, o null in caso di errore
     */
    fun inCommunicateThru(rawData: ByteArray): ByteArray? {
        val response = sendCommand(CMD_IN_COMMUNICATE_THRU, rawData) ?: return null
        if (response.isEmpty()) return null
        // response[0] = Status byte
        val status = response[0].toInt() and 0xFF
        if (status != 0x00) {
            Log.e(TAG, "InCommunicateThru errore status: 0x${status.toString(16)}")
            return null
        }
        // Il resto è la risposta del tag
        return if (response.size > 1) response.copyOfRange(1, response.size) else ByteArray(0)
    }

    // ────────────────────────────────────────────────────────────────────
    // Metodi privati
    // ────────────────────────────────────────────────────────────────────

    /**
     * Invia un comando PN532 e restituisce i dati di risposta (senza header).
     */
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

    /**
     * Costruisce un frame PN532 completo.
     */
    fun buildFrame(tfi: Byte, cmd: Byte, data: ByteArray): ByteArray {
        val payloadLen = 1 + 1 + data.size  // TFI + CMD + DATA
        val frame = ByteArray(6 + payloadLen + 2)
        var idx = 0

        frame[idx++] = 0x00              // Preamble
        frame[idx++] = 0x00              // Start code 1
        frame[idx++] = 0xFF.toByte()     // Start code 2
        frame[idx++] = payloadLen.toByte()                                 // LEN
        frame[idx++] = ((payloadLen.inv() + 1) and 0xFF).toByte()         // LCS
        frame[idx++] = tfi                                                  // TFI

        // Checksum calcolato su TFI + CMD + DATA
        var dcs = tfi.toInt()
        frame[idx++] = cmd
        dcs += cmd.toInt()

        for (b in data) {
            frame[idx++] = b
            dcs += b.toInt()
        }

        frame[idx++] = ((dcs.inv() + 1) and 0xFF).toByte()  // DCS
        frame[idx]   = 0x00                                   // Postamble

        return frame
    }

    /**
     * Analizza un frame PN532 di risposta e restituisce il payload dati.
     * Verifica che il TFI sia 0xD5 e che il CMD sia cmd+1.
     */
    private fun parseFrame(raw: ByteArray, sentCmd: Byte): ByteArray? {
        // Cerca start code 0x00 0xFF
        var i = 0
        while (i < raw.size - 1) {
            if (raw[i] == 0x00.toByte() && raw[i + 1] == 0xFF.toByte()) break
            i++
        }
        if (i >= raw.size - 1) {
            Log.e(TAG, "Start code PN532 non trovato nella risposta")
            return null
        }
        i += 2

        if (i >= raw.size) return null
        val len = raw[i++].toInt() and 0xFF
        if (i >= raw.size) return null
        val lcs = raw[i++].toInt() and 0xFF
        if ((len + lcs) and 0xFF != 0) {
            Log.e(TAG, "LCS non valido: len=$len lcs=$lcs")
            return null
        }
        if (i + len > raw.size) return null

        val tfi = raw[i]
        if (tfi != TFI_PN532_TO_HOST) {
            // Potrebbe essere un ACK frame, ignoriamo
            Log.d(TAG, "TFI non D5: 0x${tfi.toInt().and(0xFF).toString(16)}")
            return null
        }

        val responseCmd = raw[i + 1]
        val expectedCmd = ((sentCmd.toInt() and 0xFF) + 1).toByte()
        if (responseCmd != expectedCmd) {
            Log.w(TAG, "CMD risposta inatteso: got 0x${responseCmd.toInt().and(0xFF).toString(16)}, expected 0x${expectedCmd.toInt().and(0xFF).toString(16)}")
        }

        // Payload = tutto dopo TFI e CMD
        val dataStart = i + 2
        val dataEnd   = i + len
        return if (dataEnd > dataStart) raw.copyOfRange(dataStart, dataEnd) else ByteArray(0)
    }

    private fun ByteArray.toHexString() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
