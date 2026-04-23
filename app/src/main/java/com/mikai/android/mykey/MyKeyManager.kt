package com.mikai.android.mykey

import android.util.Log
import com.mikai.android.nfc.Srix4kReader
import java.util.Calendar

/**
 * Gestisce la logica di business della MyKey.
 * Porta le funzioni di mykey.c: lettura/aggiunta/impostazione crediti.
 *
 * Blocchi chiave della EEPROM SRIX4K:
 *   0x05: OTP / Lock ID
 *   0x06: Countdown counter (OTP)
 *   0x07: Chip ID / blocco lock
 *   0x15 (21): Credito corrente (cifrato)
 *   0x17 (23): Credito precedente (cifrato, senza checksum)
 *   0x18, 0x19: Codice vendor (cifrato)
 *   0x1B (27): Credito precedente copia
 *   0x1C, 0x1D: Copia vendor
 *   0x25 (37): Credito corrente copia (cifrato)
 *   0x27 (39): Credito precedente copia
 *   0x34-0x3B: Storico transazioni (8 slot)
 *   0x3C (60): Puntatore transazione corrente
 */
class MyKeyManager(private val reader: Srix4kReader) {

    companion object {
        private const val TAG = "MyKeyManager"

        // Valori blocchi 0x18/0x19 per chiave resettata (senza vendor associato)
        private const val BLOCK_18_RESET = 0x8FCD0F48u
        private const val BLOCK_19_RESET = 0xC0820007u
    }

    private val eeprom get() = reader.eeprom
    private val uid    get() = reader.uid

    /** Chiave di cifratura calcolata dall'UID e dal vendor */
    var encryptionKey: ULong = 0UL
        private set

    /**
     * Calcola la chiave di cifratura dopo la lettura della carta.
     * Va chiamata subito dopo Srix4kReader.initialize().
     */
    fun recalculateKey() {
        encryptionKey = MikaiCrypto.calculateEncryptionKey(eeprom, uid)
        Log.d(TAG, "Chiave cifratura: 0x${encryptionKey.toString(16).uppercase().padStart(16,'0')}")
    }

    /**
     * Controlla se la MyKey è resettata (non associata ad alcun vendor).
     */
    fun isReset(): Boolean =
        eeprom[0x18] == BLOCK_18_RESET && eeprom[0x19] == BLOCK_19_RESET

    /**
     * Controlla se la MyKey ha il Lock ID (protezione sconosciuta).
     * Se il blocco 0x05 ha l'ultimo byte = 0x7F e il checksum del blocco 0x21
     * non corrisponde, la carta è bloccata.
     */
    fun hasLockId(): Boolean {
        if ((eeprom[0x05] and 0x000000FFu) != 0x7Fu) return false

        var creditCheck = eeprom[0x21] xor encryptionKey.toUInt()
        creditCheck = MikaiCrypto.encodeDecodeBlock(creditCheck)

        val storedChecksum = (creditCheck shr 24) and 0xFFu
        val recalculated = MikaiCrypto.calculateBlockChecksum(creditCheck and 0x00FFFFFFu, 0x21u)
        val recalcChecksum = (recalculated shr 24) and 0xFFu

        return storedChecksum != recalcChecksum
    }

    /**
     * Legge il credito corrente in centesimi dal blocco 0x21.
     * Decifra il blocco con la chiave di cifratura.
     *
     * @return credito in centesimi, o -1 in caso di errore
     */
    fun getCurrentCreditCents(): Int {
        var creditBlock = eeprom[0x21] xor encryptionKey.toUInt()
        creditBlock = MikaiCrypto.encodeDecodeBlock(creditBlock)
        // Il credito è nei bit [15:0] del blocco decifrato
        return (creditBlock and 0xFFFFu).toInt()
    }

    /**
     * Formatta il credito come stringa euro.
     */
    fun getCreditFormatted(): String {
        val cents = getCurrentCreditCents()
        if (cents < 0) return "—"
        return "%.2f€".format(cents / 100.0)
    }

    /**
     * Aggiunge centesimi al credito corrente.
     * Porta la funzione MyKeyAddCents() di mykey.c.
     *
     * Il credito viene aggiunto in incrementi standard (2€, 1€, 0,50€, 0,20€, 0,10€, 0,05€).
     * Ogni incremento viene registrato nello storico transazioni.
     *
     * @param cents centesimi da aggiungere
     * @param calendar data della ricarica
     * @return true se l'operazione è riuscita
     */
    fun addCents(cents: Int, calendar: Calendar = Calendar.getInstance()): Result<Unit> {
        if (hasLockId()) {
            return Result.failure(Exception("La carta ha una protezione Lock ID sconosciuta"))
        }
        if (isReset()) {
            return Result.failure(Exception("La carta non è associata ad alcun vendor"))
        }
        if (eeprom[0x06] == 0u) {
            return Result.failure(Exception("La carta non è associata ad alcun vendor (block6=0)"))
        }
        if (cents <= 0) {
            return Result.failure(Exception("Importo non valido: $cents centesimi"))
        }

        val day   = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val year  = calendar.get(Calendar.YEAR) - 2000  // anni da 2000

        var remaining = cents
        var currentCredit = getCurrentCreditCents()
        val previousCredit = currentCredit

        var txOffset = getCurrentTransactionOffset()

        // Aggiungi in incrementi standard come nel MyKeyAddCents
        while (remaining > 0) {
            val increment = when {
                remaining >= 200 -> 200
                remaining >= 100 -> 100
                remaining >= 50  -> 50
                remaining >= 20  -> 20
                remaining >= 10  -> 10
                remaining >= 5   -> 5
                else             -> { remaining = 0; break }
            }
            remaining -= increment
            currentCredit += increment

            // Avanza il puntatore di transazione (circolare 0-7)
            txOffset = if (txOffset == 7) 0 else txOffset + 1

            // Scrive nello storico transazioni (blocchi 0x34..0x3B)
            // Formato: [day:5][month:4][year:7][credit:16] → ma nel codice originale:
            // day << 27 | month << 23 | year << 16 | credit
            val historyBlock = ((day and 0x1F) shl 27) or
                               ((month and 0x0F) shl 23) or
                               ((year and 0x7F) shl 16) or
                               (currentCredit and 0xFFFF)
            reader.modifyBlock(0x34 + txOffset, historyBlock.toUInt())
        }

        // Scrive il credito corrente nei blocchi 0x21 e 0x25 (cifrato)
        writeCreditBlock(0x21, currentCredit.toUInt())
        writeCreditBlock(0x25, currentCredit.toUInt())

        // Scrive il credito precedente nei blocchi 0x23 e 0x27 (senza cifratura)
        writePreviousCreditBlock(0x23, previousCredit.toUInt())
        writePreviousCreditBlock(0x27, previousCredit.toUInt())

        // Aggiorna il puntatore transazione nel blocco 0x3C
        writeTransactionPointer(txOffset)

        Log.d(TAG, "Credito aggiornato: ${previousCredit} → ${currentCredit} centesimi")
        return Result.success(Unit)
    }

    /**
     * Azzera lo storico e imposta un credito specifico.
     * Porta la funzione MyKeySetCents() di mykey.c.
     *
     * @param cents credito da impostare in centesimi
     * @param calendar data dell'operazione
     */
    fun setCents(cents: Int, calendar: Calendar = Calendar.getInstance()): Result<Unit> {
        // Backup dei blocchi rilevanti per eventuale ripristino
        val backup21 = eeprom[0x21]
        val backupHistory = UIntArray(9) { eeprom[0x34 + it] }

        // Azzera il credito corrente (blocco 0x21 → 0)
        writeCreditBlock(0x21, 0u)

        // Azzera lo storico transazioni (blocchi 0x34-0x3B → 0xFFFFFFFF)
        for (i in 0..8) {
            reader.modifyBlock(0x34 + i, 0xFFFFFFFFu)
        }

        // Aggiunge il nuovo credito
        val result = addCents(cents, calendar)
        if (result.isFailure) {
            // Ripristina lo stato precedente in caso di errore
            reader.modifyBlock(0x21, backup21)
            for (i in 0..8) reader.modifyBlock(0x34 + i, backupHistory[i])
            return result
        }

        return Result.success(Unit)
    }

    // ────────────────────────────────────────────────────────────────────
    // Metodi privati
    // ────────────────────────────────────────────────────────────────────

    /**
     * Ottiene la posizione corrente del puntatore transazione dal blocco 0x3C.
     * Porta la funzione getCurrentTransactionOffset() di mykey.c.
     */
    private fun getCurrentTransactionOffset(): Int {
        val block3C = eeprom[0x3C]

        // Se primo utilizzo, restituisce 7 (il prossimo sarà 0)
        if (block3C == 0xFFFFFFFFu) return 7

        // Decifra il puntatore
        var current = block3C xor (eeprom[0x07] and 0x00FFFFFFu)
        current = MikaiCrypto.encodeDecodeBlock(current)

        val offset = ((current shr 16) and 0xFFu).toInt()
        return if (offset > 7) 7 else offset
    }

    /**
     * Scrive un blocco credito (0x21 o 0x25) con checksum + encode + XOR chiave.
     */
    private fun writeCreditBlock(blockNum: Int, cents: UInt) {
        var block = cents and 0x0000FFFFu
        block = MikaiCrypto.calculateBlockChecksum(block, blockNum.toUInt())
        block = MikaiCrypto.encodeDecodeBlock(block)
        block = block xor encryptionKey.toUInt()
        reader.modifyBlock(blockNum, block)
    }

    /**
     * Scrive un blocco credito precedente (0x23 o 0x27) con checksum + encode (senza XOR).
     */
    private fun writePreviousCreditBlock(blockNum: Int, cents: UInt) {
        var block = cents and 0x0000FFFFu
        block = MikaiCrypto.calculateBlockChecksum(block, blockNum.toUInt())
        block = MikaiCrypto.encodeDecodeBlock(block)
        reader.modifyBlock(blockNum, block)
    }

    /**
     * Scrive il puntatore transazione nel blocco 0x3C con encode + XOR blocco 0x07.
     */
    private fun writeTransactionPointer(offset: Int) {
        var block3C = (offset and 0x07) shl 16
        block3C = MikaiCrypto.calculateBlockChecksum(block3C.toUInt(), 0x3Cu).toInt()
        val encoded = MikaiCrypto.encodeDecodeBlock(block3C.toUInt())
        val xored = encoded xor (eeprom[0x07] and 0x00FFFFFFu)
        reader.modifyBlock(0x3C, xored)
    }
}
