# Mikai Android

App Android per ricaricare credito su **MyKey** (SRIX4K) usando un lettore **ACR122U** collegato via **USB OTG**.

Basata sulla libreria open-source [libmikai](https://telegram.me/mikaidownload).

---

## Architettura

```
MainActivity
    └── MainViewModel
            ├── Acr122uDevice      (USB Host API → CCID)
            ├── Pn532              (frame PN532)
            ├── Srix4kReader       (comandi ISO14443B2SR)
            └── MyKeyManager       (logica crediti, crittografia)
```

### Livelli (come in libmikai)

| Layer | Classe                 | Descrizione                                 |
|-------|------------------------|---------------------------------------------|
| 4     | `MyKeyManager`         | API pubblica: leggi/aggiungi/imposta credito |
| 3     | `MikaiCrypto`          | Encode/decode blocchi, checksum, chiave SK   |
| 2     | `Srix4kReader`         | Comandi SRIX4K (READ_BLOCK, WRITE_BLOCK…)   |
| 1     | `Pn532`                | Frame PN532, InCommunicateThru              |
| 0     | `Acr122uDevice`        | USB CCID → ACR122U fisico                   |

---

## Requisiti

- Android 7.0+ (API 24)
- Telefono/tablet con porta **USB OTG** (host)
- Cavo OTG + lettore **ACR122U**
- La MyKey deve avere un vendor associato (non resettata)

---

## Build

1. Apri il progetto in **Android Studio** (Hedgehog o più recente)
2. Collega il tuo dispositivo Android
3. Premi **Run**

---

## Funzionamento

### Connessione lettore
1. Collega l'ACR122U con un cavo OTG
2. L'app si avvia automaticamente (o rilevata in background)
3. Concedi il permesso USB quando richiesto

### Lettura carta
1. Premi **Leggi MyKey**
2. Avvicina la carta al lettore ACR122U
3. L'app mostra UID e credito corrente

### Ricarica
- **Aggiungi**: somma l'importo al credito esistente
- **Imposta**: azzera lo storico e imposta il credito esatto

---

## Protocollo tecnico

### ACR122U → Android
- Interfaccia: **USB CCID** (class 0x0B)
- Comando usato: **PC_to_RDR_Escape (0x6B)** per inviare frame PN532 direttamente

### PN532 → SRIX4K
- Per leggere SRIX4K il PN532 richiede una sequenza specifica:
  1. `InListPassiveTarget` con `BrTy=0x03` (ISO14443B) — configura registri interni
  2. `InListPassiveTarget` con `BrTy=0x06` (ISO14443B2SR) — trova il tag
  3. `InCommunicateThru` — per tutti i comandi SRIX successivi
  
  Fonte: [libnfc issue #436](https://github.com/nfc-tools/libnfc/issues/436)

### Comandi SRIX4K (via InCommunicateThru)
| Byte | Descrizione            |
|------|------------------------|
| 0x0B | GET_UID (→ 8 byte)     |
| 0x08 [blockNum] | READ_BLOCK (→ 4 byte) |
| 0x09 [blockNum] [b0..b3] | WRITE_BLOCK |

---

## Crittografia MyKey

La chiave di sessione è: **SK = UID × VENDOR × OTP**

- **OTP**: derivato dal blocco 6 (countdown counter invertito)
- **VENDOR**: estratto dai blocchi 0x18/0x19 (dopo decode)
- Il credito è nel blocco 0x21, cifrato: `block21 = encode(credit) XOR SK`

La funzione `encodeDecodeBlock()` è una permutazione simmetrica su coppie di bit a 32 bit (identica nel libmikai originale).

---

## Note importanti

- ⚠️ Questa app è a scopo **educativo/personale**
- ⚠️ Modificare le carte scolastiche potrebbe violare i termini di servizio del tuo istituto
- ⚠️ Alcuni istituti usano protezione **Lock ID** — in tal caso la ricarica non è possibile
- Il progetto si basa su libmikai (licenza proprietaria) — rispettarne i termini

---

## Troubleshooting

**L'ACR122U non viene rilevato**
- Verifica che il telefono supporti USB OTG
- Prova a disabilitare e riabilitare il debug USB
- Controlla il cavo OTG (non tutti i cavi supportano la modalità host)

**"Nessun tag SRIX4K trovato"**
- Avvicina la carta al lettore (distanza < 3cm)
- La carta potrebbe non essere una MyKey compatibile (SRIX4K)

**"Lock ID rilevato"**
- La carta ha una protezione implementata da alcuni istituti
- Non è possibile ricaricarla con questo metodo

**Errore scrittura**
- Tieni la carta ferma durante la scrittura
- Non allontanare la carta mentre lampeggia il LED del lettore
