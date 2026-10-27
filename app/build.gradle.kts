import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Serial transport selected at build time.
 *
 * "native" (default) is the production behaviour: the app opens /dev/ttyS1 through
 * the vendor native library. "tcp" is for running the app on the PC emulator,
 * where that ARM-only library cannot load: the same Modbus traffic goes to the
 * board simulator over TCP instead.
 *
 * The value can come from `-PvendingSerial=tcp` or from the untracked
 * `local.properties` (vendingSerial=tcp). Either way the release build overrides
 * it back to "native", so a deployable APK never routes the board over the network.
 */
fun vendingLocalProperty(name: String): String? = runCatching {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return@runCatching null
    Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)
}.getOrNull()

fun vendingProperty(name: String, fallback: String): String {
    val value = (project.findProperty(name) as String?)
        ?: (project.rootProject.findProperty(name) as String?)
        ?: vendingLocalProperty(name)
    return value?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
}

val vendingSerialTransport = vendingProperty("vendingSerial", "native").lowercase()
require(vendingSerialTransport == "native" || vendingSerialTransport == "tcp") {
    "-PvendingSerial (o vendingSerial en local.properties) debe ser 'native' o 'tcp' (recibido: '$vendingSerialTransport')"
}
val vendingSerialEndpoint = vendingProperty("vendingSimEndpoint", "10.0.2.2:9100")

// Backend base URL. Empty means automatic: the DEVELOPMENT backend below for a native build, and the
// local mock backend for a simulator build (so the kiosk flow stays testable offline).
//
// LOS DOS APUNTAN AL BACKEND YA DESPLEGADO (backend.boxipago.com.bo): la URL vieja
// (boxipagobackend.pagofacil.com.bo) NO se usa desde esta APK —es otro flujo, en produccion, y no
// se lo puede interferir—.
val vendingDevelopmentBackend = "https://backend.boxipago.com.bo"
// El de DESPLIEGUE es el que queda grabado en la release (cerrojo 3). Tiene que ser una URL publica
// HTTPS: las pasarelas de pago solo confirman contra un callback alcanzable desde internet.
val vendingDeployBackend = "https://backend.boxipago.com.bo"
val vendingBackendUrl = vendingProperty("vendingBackendUrl", "").ifEmpty {
    if (vendingSerialTransport == "tcp") "http://10.0.2.2:9200" else vendingDevelopmentBackend
}

android {
    namespace = "com.vending.kiosk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vending.kiosk"
        minSdk = 25
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-bootstrap"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SERIAL_TRANSPORT", "\"$vendingSerialTransport\"")
        buildConfigField("String", "SERIAL_SIM_ENDPOINT", "\"$vendingSerialEndpoint\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$vendingBackendUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Cerrojo 1: un APK de despliegue siempre habla con el puerto serie
            // nativo. Aunque la propiedad quede seteada en la PC o en el IDE, el
            // modo simulador no puede terminar en una release.
            buildConfigField("String", "SERIAL_TRANSPORT", "\"native\"")
            buildConfigField("String", "SERIAL_SIM_ENDPOINT", "\"10.0.2.2:9100\"")
            // Cerrojo 3: y siempre contra el backend de DESPLIEGUE, nunca contra el simulador
            // local, aunque la propiedad quede seteada en la maquina. El de despliegue se cambia
            // arriba.
            buildConfigField("String", "BACKEND_BASE_URL", "\"$vendingDeployBackend\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true

        // SERIAL_TRANSPORT / SERIAL_SIM_ENDPOINT are read at runtime to decide
        // whether the app talks to /dev/ttyS1 or to the PC board simulator.
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(project(":integration-backend"))
    implementation(project(":integration-serial"))
    implementation(project(":kiosk-device"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
