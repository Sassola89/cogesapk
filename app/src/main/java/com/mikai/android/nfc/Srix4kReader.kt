package com.mikai.android.nfc

import android.util.Log

/**
 * Protocollo SRIX4K per tag ISO14443B2SR (MyKey).
 *
 * Porta la logica di reader.c e srix.c della libmikai.
 *
 * Comandi SRIX4K (inviati via PN532 InCommunicateThru):
 *   0x0B              → GET_UID (risposta: 8 byte)
 *   0x08 [blockNum]   → READ_BLOCK (risposta: 4 byte)
 *   0x09 [blockNum] [b0][b1][b2][b3] → WRITE_BLOCK (risposta: nessuna)
 *
 * La EEPROM SRIX4K è composta da 128 blocchi da 4 byte ciascuno (512 byte totali).
 */
class Srix4kReader(private val pn532: Pn532) {

    companion object {
        private const val TAG = "Srix4kReader"

        const val BLOCK_COUNT   = 128
        const val BLOCK_SIZE    = 4    // byte per blocco
        const val UID_SIZE      = 8    // byte

        // Comandi SRIX4K
        private const val CMD_GET_UID    = 0x0B.toByte()
        private const val CMD_READ_BLOCK = 0x08.toByte()
        private const val CMD_WRITE_BLOCK = 0x09.toByte()

        // Codice produttore ST Microelectronics nel byte 7 e 6 dell'UID
        private const val ST_UID_BYTE7 = 0xD0.toByte()
        private const val ST_UID_BYTE6 = 0x02.toByte()
    }

    /** UID del tag come ULong (8 byte) */
    var uid: ULong = 0UL
        private set

    /** EEPROM completa: 128 blocchi da 4 byte, rappresentati come UInt */
    val eeprom: UIntArray = UIntArray(BLOCK_COUNT)

    /** Flag per tracciare i blocchi modificati che devono essere scritti */
    private val modifiedFlags = BooleanArray(BLOCK_COUNT)

    /**
     * Inizializza il lettore con il tag SRIX4K:
     * 1. Inizializza ISO14443B (per configurare registri PN532)
     * 2. Trova il tag ISO14443B2SR
     * 3. Legge UID e tutti i blocchi
     *
     * @return true se l'inizializzazione è avvenuta correttamente
     */
    fun initialize(): Boolean {
        // Step 1: inizializza ISO14443B per configurare i registri PN532
        // (necessario per SRIX4K, vedi commento nel reader.c della libmikai)
        pn532.initISO14443B()

        // Step 2: trova il tag SRIX4K
        if (!pn532.listPassiveTargetSrix4k()) {
            Log.e(TAG, "Nessun tag SRIX4K trovato")
            return false
        }

        // Step 3: leggi UID
        if (!readUid()) {
            Log.e(TAG, "Errore lettura UID")
            return false
        }

        // Step 4: leggi tutti i blocchi
        if (!readAllBlocks()) {
            Log.e(TAG, "Errore lettura blocchi EEPROM")
            return false
        }

        Log.d(TAG, "SRIX4K inizializzato. UID=${uid.toString(16).uppercase().padStart(16, '0')}")
        return true
    }

    /**
     * Legge l'UID del tag (8 byte).
     * Verifica che sia un tag ST Microelectronics valido.
     */
    private fun readUid(): Boolean {
        val response = pn532.inCommunicateThru(byteArrayOf(CMD_GET_UID)) ?: return false

        if (response.size != UID_SIZE) {
            Log.e(TAG, "UID lunghezza non valida: ${response.size}")
            return false
        }

        // Verifica codice produttore (byte 7 = 0xD0, byte 6 = 0x02 per ST SRIX4K)
        if (response[7] != ST_UID_BYTE7 || response[6] != ST_UID_BYTE6) {
            Log.e(TAG, "Codice produttore non valido: ${response[7].toInt().and(0xFF).toString(16)} ${response[6].toInt().and(0xFF).toString(16)}")
            // Non bloccare - potrebbe essere una variante compatibile
        }

        // Converte da byte array a ULong (little endian come nel reader.c)
        uid = (response[7].toLong().and(0xFF) shl 56 or
               response[6].toLong().and(0xFF) shl 48 or
               response[5].toLong().and(0xFF) shl 40 or
               response[4].toLong().and(0xFF) shl 32 or
               response[3].toLong().and(0xFF) shl 24 or
               response[2].toLong().and(0xFF) shl 16 or
               response[1].toLong().and(0xFF) shl 8  or
               response[0].toLong().and(0xFF)).toULong()

        return true
    }

    /**
     * Legge un singolo blocco dalla SRIX4K.
     * Ritenta automaticamente se il tag si allontana temporaneamente.
     *
     * @param blockNum numero del blocco (0-127)
     * @return 4 byte del blocco o null in caso di errore
     */
    fun readBlock(blockNum: Int): ByteArray? {
        require(blockNum in 0 until BLOCK_COUNT) { "blockNum fuori range: $blockNum" }

        var attempts = 0
        while (attempts < 3) {
            val cmd = byteArrayOf(CMD_READ_BLOCK, blockNum.toByte())
            val response = pn532.inCommunicateThru(cmd)

            if (response != null && response.size == BLOCK_SIZE) {
                return response
            }

            // Tag temporaneamente non presente, ri-seleziona
            Log.d(TAG, "Retry lettura blocco $blockNum (tentativo ${++attempts})")
            pn532.initISO14443B()
            pn532.listPassiveTargetSrix4k()
        }

        Log.e(TAG, "Impossibile leggere blocco $blockNum dopo 3 tentativi")
        return null
    }

    /**
     * Legge tutti i 128 blocchi della EEPROM SRIX4K.
     */
    private fun readAllBlocks(): Boolean {
        for (i in 0 until BLOCK_COUNT) {
            val blockData = readBlock(i) ?: return false
            // Interpreta i 4 byte come UInt (little endian)
            eeprom[i] = (blockData[0].toInt().and(0xFF) or
                        (blockData[1].toInt().and(0xFF) shl 8) or
                        (blockData[2].toInt().and(0xFF) shl 16) or
                        (blockData[3].toInt().and(0xFF) shl 24)).toUInt()
            modifiedFlags[i] = false
        }
        Log.d(TAG, "Tutti i 128 blocchi letti")
        return true
    }

    /**
     * Modifica un blocco in memoria e lo marca come "da scrivere".
     * La scrittura effettiva avviene con writeAllModified().
     */
    fun modifyBlock(blockNum: Int, value: UInt) {
        require(blockNum in 0 until BLOCK_COUNT)
        if (eeprom[blockNum] != value) {
            eeprom[blockNum] = value
            modifiedFlags[blockNum] = true
        }
    }

    /**
     * Scrive fisicamente sul tag tutti i blocchi marcati come modificati.
     * Verifica la scrittura rileggendo ogni blocco.
     *
     * @return true se tutti i blocchi sono stati scritti correttamente
     */
    fun writeAllModified(): Boolean {
        for (i in 0 until BLOCK_COUNT) {
            if (!modifiedFlags[i]) continue

            if (!writeBlock(i, eeprom[i])) {
                Log.e(TAG, "Errore scrittura blocco $i")
                return false
            }
            modifiedFlags[i] = false
            Log.d(TAG, "Blocco $i scritto: 0x${eeprom[i].toString(16).uppercase().padStart(8,'0')}")
        }
        return true
    }

    /**
     * Scrive un blocco sul tag SRIX4K e verifica la scrittura rileggendo.
     * Ritenta finché la verifica non passa (come nel reader.c).
     */
    private fun writeBlock(blockNum: Int, value: UInt): Boolean {
        val b0 = (value and 0xFFu).toByte()
        val b1 = ((value shr 8) and 0xFFu).toByte()
        val b2 = ((value shr 16) and 0xFFu).toByte()
        val b3 = ((value shr 24) and 0xFFu).toByte()

        val writeCmd = byteArrayOf(CMD_WRITE_BLOCK, blockNum.toByte(), b0, b1, b2, b3)

        var attempts = 0
        while (attempts < 5) {
            // Verifica presenza tag
            val checkPresence = pn532.inCommunicateThru(byteArrayOf(CMD_READ_BLOCK, blockNum.toByte()))
            if (checkPresence == null) {
                pn532.initISO14443B()
                pn532.listPassiveTargetSrix4k()
            }

            // Scrivi il blocco
            pn532.inCommunicateThru(writeCmd)  // la SRIX4K non invia risposta alla scrittura

            // Verifica leggendo di nuovo
            val verify = readBlock(blockNum) ?: continue
            val written = (verify[0].toInt().and(0xFF) or
                          (verify[1].toInt().and(0xFF) shl 8) or
                          (verify[2].toInt().and(0xFF) shl 16) or
                          (verify[3].toInt().and(0xFF) shl 24)).toUInt()

            if (written == value) return true
            attempts++
        }
        return false
    }

    /** Stringa UID formattata per display */
    fun getUidString(): String =
        uid.toString(16).uppercase().padStart(16, '0').chunked(2).joinToString(":")
}
