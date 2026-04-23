# 🔧 Come compilare Mikai Android (senza Android Studio)

Ci sono **3 modi** per ottenere l'APK installabile:

---

## 🥇 Metodo 1: GitHub Actions (CONSIGLIATO — nessuna installazione)

GitHub compila l'APK gratis per te, in cloud. Bastano 5 minuti.

### Passaggi

1. **Crea un account su GitHub** (gratis): https://github.com/signup

2. **Crea un nuovo repository**:
   - Vai su https://github.com/new
   - Nome: `mikai-android`
   - Visibilità: **Private** (consigliato)
   - Premi "Create repository"

3. **Carica i file**:
   - Nella pagina del repository, clicca **"uploading an existing file"**
   - Decomprimi il file `MikaiAndroid.zip` sul tuo PC
   - Trascina **tutti i file e cartelle** nella pagina GitHub
   - Scrivi un messaggio tipo "Prima versione" e premi **Commit changes**

4. **Avvia la compilazione** (parte automaticamente al push, oppure):
   - Vai su tab **Actions**
   - Clicca su **"Build APK"** nella lista a sinistra
   - Premi il pulsante **"Run workflow"** → **"Run workflow"**

5. **Scarica l'APK**:
   - Aspetta ~3-5 minuti che la build diventi verde ✅
   - Clicca sulla build completata
   - In fondo alla pagina, sotto **Artifacts**, clicca **"MikaiAndroid-debug"**
   - Scarica il file ZIP → dentro c'è `app-debug.apk`

6. **Installa sul telefono**:
   - Trasferisci l'APK sul telefono (via USB, email, Drive, ecc.)
   - Sul telefono vai su **Impostazioni → Sicurezza → Sorgenti sconosciute** (abilitalo)
   - Apri l'APK e installa

---

## 🥈 Metodo 2: Build locale su Windows (da riga di comando)

### Prerequisiti (una tantum)

1. **Java 17**: scarica da https://adoptium.net/
   - Scegli "Temurin 17 LTS" → Windows x64 Installer
   - Installa e riavvia il PC

2. **Android Command Line Tools**: scarica da https://developer.android.com/studio#command-tools
   - Scarica "Command line tools only" per Windows
   - Decomprimi in `C:\Android\cmdline-tools\latest\`

3. **Accetta le licenze e installa SDK**:
   Apri un terminale (CMD o PowerShell) e digita:
   ```cmd
   set ANDROID_HOME=C:\Android
   C:\Android\cmdline-tools\latest\bin\sdkmanager.bat --licenses
   C:\Android\cmdline-tools\latest\bin\sdkmanager.bat "platforms;android-34" "build-tools;34.0.0"
   ```

### Compilazione

```cmd
cd C:\percorso\a\MikaiAndroid
set ANDROID_HOME=C:\Android
gradlew.bat assembleDebug
```

L'APK è in: `app\build\outputs\apk\debug\app-debug.apk`

---

## 🥉 Metodo 3: Build locale su Mac/Linux

### Prerequisiti (una tantum)

1. **Java 17**:
   ```bash
   # Mac con Homebrew:
   brew install openjdk@17

   # Ubuntu/Debian:
   sudo apt install openjdk-17-jdk
   ```

2. **Android Command Line Tools**:
   ```bash
   # Scarica da https://developer.android.com/studio#command-tools
   mkdir -p ~/Android/cmdline-tools/latest
   cd ~/Android/cmdline-tools/latest
   unzip ~/Downloads/commandlinetools-linux-*.zip -d .
   mv cmdline-tools/* .
   ```

3. **Installa SDK**:
   ```bash
   export ANDROID_HOME=$HOME/Android
   export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

   sdkmanager --licenses
   sdkmanager "platforms;android-34" "build-tools;34.0.0"
   ```

### Compilazione

```bash
cd /percorso/a/MikaiAndroid
export ANDROID_HOME=$HOME/Android
chmod +x gradlew
./gradlew assembleDebug
```

L'APK è in: `app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 Installazione sul telefono

### Metodo A — Trasferimento via cavo USB (PC + Android)
```bash
# Con adb (incluso in platform-tools):
adb install app-debug.apk
```

### Metodo B — Trasferimento manuale
1. Copia l'APK sul telefono via cavo, email, Google Drive, WhatsApp a te stesso, ecc.
2. Apri il file manager del telefono
3. Naviga dove hai salvato l'APK e toccalo
4. Se compare "Non consentito": vai in **Impostazioni → App → Chrome** (o il browser che usi) → **Installa app sconosciute** → attiva

---

## ❓ Domande frequenti

**Q: L'app è sicura da installare?**
A: È un'app debug (non firmata con chiave privata), quindi Android mostra l'avviso "sorgente sconosciuta". Il codice è quello che hai visto nel progetto.

**Q: Funziona su tutti i telefoni?**
A: Richiede Android 7.0+ e supporto USB OTG (Host). La maggior parte dei telefoni moderni lo supporta. Verifica con un'app come "USB OTG Checker".

**Q: Il cavo OTG giusto?**
A: Serve un cavo **USB OTG** (On-The-Go) che converte la porta del telefono in host USB. Costa 2-5€ su Amazon. Assicurati che il connettore corrisponda alla porta del tuo telefono (USB-C, Micro-USB, ecc.).
