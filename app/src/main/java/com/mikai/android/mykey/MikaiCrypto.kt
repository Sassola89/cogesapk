package com.mikai.android.mykey

/**
 * Funzioni crittografiche della MyKey, portate da mykey.c della libmikai.
 *
 * La MyKey usa un sistema proprietario basato su:
 *   - XOR swap su coppie di bit nel blocco (encodeDecodeBlock)
 *   - Checksum per blocco (calculateBlockChecksum)
 *   - Chiave di cifratura: SK = UID × VENDOR × OTP (calculateEncryptionKey)
 */
object MikaiCrypto {

    /**
     * Codifica o decodifica un blocco MyKey (operazione simmetrica).
     * Porta la funzione encodeDecodeBlock() di mykey.c.
     *
     * L'algoritmo esegue 3 passaggi di swap bit usando XOR:
     * opera su coppie di bit nella parola a 32 bit.
     */
    fun encodeDecodeBlock(block: UInt): UInt {
        var b = block

        // Passaggio 1
        b = b xor ((b and 0x00C00000u) shl 6 or ((b and 0x0000C000u) shl 12) or
                   ((b and 0x000000C0u) shl 18) or ((b and 0x000C0000u) shr 6) or
                   ((b and 0x00030000u) shr 12) or ((b and 0x00000300u) shr 6))

        // Passaggio 2
        b = b xor ((b and 0x30000000u) shr 6 or ((b and 0x0C000000u) shr 12) or
                   ((b and 0x03000000u) shr 18) or ((b and 0x00003000u) shl 6) or
                   ((b and 0x00000030u) shl 12) or ((b and 0x0000000Cu) shl 6))

        // Passaggio 3 (identico al passaggio 1)
        b = b xor ((b and 0x00C00000u) shl 6 or ((b and 0x0000C000u) shl 12) or
                   ((b and 0x000000C0u) shl 18) or ((b and 0x000C0000u) shr 6) or
                   ((b and 0x00030000u) shr 12) or ((b and 0x00000300u) shr 6))

        return b
    }

    /**
     * Calcola il checksum di un blocco e lo inserisce nel byte più significativo.
     * Porta la funzione calculateBlockChecksum() di mykey.c.
     *
     * Checksum = 0xFF - blockNum - (somma dei 6 nibble bassi del blocco)
     * Il risultato viene inserito nei bit [31:24] del blocco.
     *
     * @param block  valore a 32 bit del blocco (senza checksum nel byte alto)
     * @param blockNum numero del blocco (0-127)
     * @return blocco con checksum nel byte [31:24]
     */
    fun calculateBlockChecksum(block: UInt, blockNum: UInt): UInt {
        val checksum = (0xFFu - blockNum -
                        (block and 0x0Fu) -
                        ((block shr 4) and 0x0Fu) -
                        ((block shr 8) and 0x0Fu) -
                        ((block shr 12) and 0x0Fu) -
                        ((block shr 16) and 0x0Fu) -
                        ((block shr 20) and 0x0Fu)) and 0xFFu

        return (block and 0x00FFFFFFu) or (checksum shl 24)
    }

    /**
     * Calcola la chiave di cifratura (SK) della MyKey.
     * Porta la funzione calculateEncryptionKey() di mykey.c.
     *
     * Algoritmo:
     *   OTP  = (~reverse_bytes(block6) + 1)       [conta le ricariche passate]
     *   MK   = UID × VENDOR
     *   SK   = MK × OTP
     *
     * dove VENDOR è estratto dai blocchi 0x18 e 0x19.
     *
     * @param eeprom array EEPROM a 128 elementi
     * @param uid    UID del tag come ULong
     * @return chiave di cifratura a 64 bit
     */
    fun calculateEncryptionKey(eeprom: UIntArray, uid: ULong): ULong {
        // Calcola OTP dal blocco 6 (countdown counter)
        val block6 = eeprom[0x06]
        val block6Reversed = ((block6 shl 24) or
                ((block6 and 0x0000FF00u) shl 8) or
                ((block6 and 0x00FF0000u) shr 8) or
                (block6 shr 24))
        val otp = (block6Reversed.inv() + 1u).toULong() and 0xFFFFFFFFuL

        // Estrae il codice vendor dai blocchi 0x18 e 0x19 (dopo decode)
        val block18Decoded = encodeDecodeBlock(eeprom[0x18])
        val block19Decoded = encodeDecodeBlock(eeprom[0x19])

        val vendor = (((block18Decoded shl 16) or (block19Decoded and 0x0000FFFFu)) + 1u)
                     .toULong() and 0xFFFFFFFFuL

        // SK = UID × VENDOR × OTP
        return uid * vendor * otp
    }

    /**
     * Calcola il numero di giorni tra il 1/1/1995 e la data specificata.
     * Porta la funzione daysDifference() di mykey.c.
     * Usato per codificare la data nelle transazioni.
     */
    fun daysDifference(day: Int, month: Int, year: Int): UInt {
        var y = year
        var m = month
        if (m < 3) {
            y--
            m += 12
        }
        return (y * 365 + y / 4 - y / 100 + y / 400 + (m * 153 + 3) / 5 + day - 728692).toUInt()
    }
}
