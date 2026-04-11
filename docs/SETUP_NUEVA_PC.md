# Setup Nueva PC - apk-nativa-vending

Este documento es la guia para dejar funcionando el proyecto en una computadora nueva.

## 1) Requisitos obligatorios

- Sistema operativo: Windows (flujo actual del equipo).
- Git instalado.
- Android Studio instalado (version reciente compatible con AGP `8.4.2`).
- JDK instalado:
  - Recomendado: `JDK 17`.
  - Tambien funciona con `JDK 21`, pero el proyecto esta configurado a nivel de compilacion/toolchain en `17`.
- Android SDK instalado con:
  - `Android SDK Platform 34`
  - `Android SDK Build-Tools` (34.x)
  - `Android SDK Platform-Tools` (para `adb`)
- Internet para que Gradle descargue dependencias.

## 2) Configuracion tecnica del proyecto (referencia)

- Gradle wrapper: `8.6` (`gradle/wrapper/gradle-wrapper.properties`)
- Android Gradle Plugin: `8.4.2` (`build.gradle.kts`)
- Kotlin plugin: `1.9.24` (`build.gradle.kts`)
- `compileSdk = 34`, `targetSdk = 34`, `minSdk = 25` (`app/build.gradle.kts`)
- Java/Kotlin target: `17` (app + modulos JVM)

## 3) Configurar variables de entorno (Windows)

### JAVA_HOME

Apuntar `JAVA_HOME` al JDK instalado, por ejemplo:

```powershell
setx JAVA_HOME "C:\Program Files\Java\jdk-17"
```

### Android SDK / adb

Ruta tipica del SDK:

`C:\Users\<USUARIO>\AppData\Local\Android\Sdk`

Agregar al `PATH`:

- `%ANDROID_HOME%\platform-tools`
- `%ANDROID_HOME%\cmdline-tools\latest\bin` (opcional pero recomendado)

Ejemplo:

```powershell
setx ANDROID_HOME "C:\Users\<USUARIO>\AppData\Local\Android\Sdk"
setx PATH "$($env:PATH);C:\Users\<USUARIO>\AppData\Local\Android\Sdk\platform-tools"
```

## 4) Clonar y abrir proyecto

```powershell
git clone <URL_DEL_REPO>
cd apk-nativa-vending
```

Abrir carpeta en Android Studio y esperar Sync de Gradle.

## 5) Ajustar `local.properties`

Verificar/crear `local.properties` en la raiz con la ruta correcta del SDK:

```properties
sdk.dir=C\:\\Users\\<USUARIO>\\AppData\\Local\\Android\\Sdk
```

Nota: la ruta del `local.properties` de otra laptop no sirve si cambia el usuario/ruta.

## 6) Compilar APK

Desde terminal en la raiz del proyecto:

```powershell
.\gradlew.bat assembleDebug
```

APK generada en:

`app\build\outputs\apk\debug\app-debug.apk`

## 7) Instalar APK por ADB

Con dispositivo conectado y `Depuracion USB` activa:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 8) Verificaciones rapidas post-setup

- El proyecto sincroniza sin errores en Android Studio.
- `assembleDebug` termina en `BUILD SUCCESSFUL`.
- `adb devices` detecta el equipo.
- Se puede instalar la APK debug.

## 9) Dependencias internas importantes (NO borrar)

- `integration-serial/libs/usdk_v1.0.jar`
- Librerias nativas en:
  - `integration-serial/src/main/jniLibs/armeabi-v7a/`
  - `integration-serial/src/main/jniLibs/arm64-v8a/`

Sin esos archivos, la parte serial/calibracion/tester puede romperse.

## 10) Contexto funcional rapido para el nuevo Codex

- Flujo principal kiosk:
  - `MainActivity` -> `KioskLoginActivity` -> `KioskMachinesActivity` -> `KioskCatalogActivity`
- Runtime serial compartido entre Tester/Kiosk:
  - `integration-serial/src/main/kotlin/com/vending/kiosk/integration/serial/runtime/VendingFlowController.kt`
- Puerto y baud esperados por hardware:
  - `/dev/ttyS1`
  - `9600`

## 11) Problemas comunes y solucion

- `adb` no reconocido:
  - faltan `platform-tools` en `PATH`.
- Error de Java/Gradle:
  - `JAVA_HOME` no apunta a JDK valido (usar 17/21).
- Error de SDK:
  - `local.properties` apunta a ruta vieja.
- Error tipo `Value <html> ... cannot be converted to JSONObject`:
  - backend devolvio HTML en vez de JSON (incidente de red/proxy/backend), no fallo de compilacion local.

## 12) Docs que debes leer primero en esa nueva PC

- `docs/PLAN.md`
- `docs/BITACORA.md`
- `docs/TODO.md`
- `docs/reporte.md`

