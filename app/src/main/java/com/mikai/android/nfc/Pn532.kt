package com.mikai.android.nfc

import android.util.Log
import com.mikai.android.usb.Acr122uDevice

class Pn532(private val device: Acr122uDevice) {

    fun getFirmwareVersion(): String? {
        val response = sendCommand(0x02) ?: return null
        if (response.size < 4) return null
        return "IC=${response[0].toInt() and 0xFF} Ver=${response[1].toInt() and 0xFF}.${response[2].toInt() and 0xFF}"
    }

    fun initISO14443B(): Boolean {
        val data = byteArrayOf(0x01, 0x03)
        return sendCommand(0x4A, data) != null
    }

    fun listPassiveTargetSrix4k(): Boolean {
        val data = byteArrayOf(0x01, 0x06)
        val response = sendCommand(0x4A, data) ?: return false
        return response.isNotEmpty() && response[0].toInt() > 0
    }

    fun inCommunicateThru(rawData: ByteArray): ByteArray? {
        val response = sendCommand(0x42, rawData) ?: return null
        if (response.isEmpty()) return null
        val status = response[0].toInt() and 0xFF
        if (status != 0x00) {
            Log.e("Pn532", "Status error: 0x${status.toString(16)}")
            return null
        }
        return if (response.size > 1) response.copyOfRange(1, response.size) else ByteArray(0)
    }

    private fun sendCommand(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray? {
        val frame = buildFrame(0xD4, cmd, data)
        val raw = device.sendEscape(frame) ?: return null
        return parseFrame(raw)
    }

    fun buildFrame(tfi: Int, cmd: Int, data: ByteArray): ByteArray {
        val payloadLen = 2 + data.size
        val frame = ByteArray(6 + payloadLen + 2)
        frame[0] = 0x00
        frame[1] = 0x00
        frame[2] = 0xFF.toByte()
        frame[3] = payloadLen.toByte()
        frame[4] = ((payloadLen.inv() + 1) and 0xFF).toByte()
        frame[5] = tfi.toByte()
        frame[6] = cmd.toByte()
        var dcs = tfi + cmd
        for (i in data.indices) {
            frame[7 + i] = data[i]
            dcs += data[i].toInt()
        }
        frame[7 + data.size] = ((dcs.inv() + 1) and 0xFF).toByte()
        frame[8 + data.size] = 0x00
        return frame
    }

    private fun parseFrame(raw: ByteArray): ByteArray? {
        var i = 0
        while (i < raw.size - 1) {
            if (raw[i] == 0x00.toByte() && raw[i + 1] == 0xFF.toByte()) break
            i++
        }
        if (i >= raw.size - 1) return null
        i += 2
        if (i >= raw.size) return null
        val len = raw[i++].toInt() and 0xFF
        if (i >= raw.size) return null
        val lcs = raw[i++].toInt() and 0xFF
        if ((len + lcs) and 0xFF != 0) return null
        if (i + len > raw.size) return null
        if (raw[i].toInt() and 0xFF != 0xD5) return null
        val dataStart = i + 2
        val dataEnd = i + len
        return if (dataEnd > dataStart) raw.copyOfRange(dataStart, dataEnd) else ByteArray(0)
    }
}
