import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.firebase.appdistribution)
}

// ---------------------------------------------------------------------------
// Deployment configuration.
//
// Nothing secret is hardcoded below. Every value is resolved at configuration
// time from, in order of precedence:
//   1. an environment variable,
//   2. an untracked properties file (keystore.properties / local.properties),
//   3. a Gradle property (gradle.properties, ~/.gradle/gradle.properties, -P).
// Run `./gradlew deployConfigCheck` to see what is resolved and what is missing.
// ---------------------------------------------------------------------------

fun loadProps(file: File): Properties = Properties().apply {
    if (file.isFile) file.inputStream().use { load(it) }
}

fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Expands a leading `~/` and resolves relative paths against the repo root. */
fun resolvePath(raw: String): File {
    val expanded = if (raw.startsWith("~/")) {
        System.getProperty("user.home") + raw.substring(1)
    } else {
        raw
    }
    val file = File(expanded)
    return if (file.isAbsolute) file else rootProject.file(expanded)
}

val localProps = loadProps(rootProject.file("local.properties"))
val keystoreProps = loadProps(
    resolvePath(System.getenv("CLASSTIME_KEYSTORE_PROPERTIES").orNullIfBlank() ?: "keystore.properties")
)

/** env var -> local.properties -> Gradle property. */
fun cfg(envName: String, propName: String): String? =
    System.getenv(envName).orNullIfBlank()
        ?: localProps.getProperty(propName).orNullIfBlank()
        ?: providers.gradleProperty(propName).orNull.orNullIfBlank()

/** Same, but checks the untracked keystore.properties first. */
fun signingCfg(envName: String, propName: String): String? =
    System.getenv(envName).orNullIfBlank()
        ?: keystoreProps.getProperty(propName).orNullIfBlank()
        ?: cfg(envName, propName)

// --- Version -----------------------------------------------------------------

val versionPropsFile = rootProject.file("version.properties")
val versionProps = loadProps(versionPropsFile)

val appVersionCode = (
    cfg("VERSION_CODE", "versionCode")
        ?: versionProps.getProperty("versionCode").orNullIfBlank()
        ?: "1"
    ).toInt()

val appVersionName = cfg("VERSION_NAME", "versionName")
    ?: versionProps.getProperty("versionName").orNullIfBlank()
    ?: "1.0"

// --- Signing -----------------------------------------------------------------
// A single fixed keystore is used for BOTH debug and release so that every APK
// this project produces carries the same signature. Android only allows an
// in-place update when the signature matches, so this is what makes "install
// over the top" work in Firebase App Tester instead of forcing an uninstall.

val ksFile = signingCfg("CLASSTIME_KEYSTORE_FILE", "storeFile")?.let { resolvePath(it) }
val ksStorePassword = signingCfg("CLASSTIME_KEYSTORE_PASSWORD", "storePassword")
val ksKeyAlias = signingCfg("CLASSTIME_KEY_ALIAS", "keyAlias") ?: "classtime"
val ksKeyPassword = signingCfg("CLASSTIME_KEY_PASSWORD", "keyPassword") ?: ksStorePassword

val signingReady = ksFile != null && ksFile.isFile &&
    ksStorePassword != null && ksKeyPassword != null

if (!signingReady) {
    logger.warn(
        "ClassTime: no fixed signing key configured - debug builds fall back to the " +
            "auto-generated debug keystore and release builds stay unsigned. " +
            "Run ./scripts/create-keystore.sh (or see ./gradlew deployConfigCheck)."
    )
}

// --- Firebase App Distribution ------------------------------------------------

val firebaseAppIdValue = cfg("FIREBASE_APP_ID", "firebaseAppId")
val firebaseTestersValue = cfg("FIREBASE_TESTERS", "firebaseTesters")
val firebaseGroupsValue = cfg("FIREBASE_GROUPS", "firebaseGroups")
val firebaseCredentialsValue = cfg("FIREBASE_SERVICE_ACCOUNT", "firebaseServiceCredentialsFile")
    ?: System.getenv("GOOGLE_APPLICATION_CREDENTIALS").orNullIfBlank()

// A service account is not the only way in. When none is supplied the plugin
// falls back to the refresh token the Firebase CLI stores after `firebase login`
// (verified against the plugin: AppDistributionEnvironmentImpl reads
// ${XDG_CONFIG_HOME:-~/.config}/configstore/firebase-tools.json), or to
// FIREBASE_TOKEN. Accept any of the three so a CLI login is not reported as a
// misconfiguration.
val firebaseCliConfigFile = File(
    System.getenv("XDG_CONFIG_HOME").orNullIfBlank() ?: "${System.getProperty("user.home")}/.config",
    "configstore/firebase-tools.json"
)
val firebaseCliLoggedIn = firebaseCliConfigFile.isFile &&
    firebaseCliConfigFile.readText().contains("refresh_token")
val firebaseTokenEnv = System.getenv("FIREBASE_TOKEN").orNullIfBlank()

val firebaseAuthMethod: String? = when {
    firebaseCredentialsValue != null -> "service account JSON"
    firebaseTokenEnv != null -> "FIREBASE_TOKEN"
    firebaseCliLoggedIn -> "Firebase CLI login"
    else -> null
}
val firebaseReleaseNotesValue = cfg("FIREBASE_RELEASE_NOTES", "firebaseReleaseNotes")
    ?: "ClassTime $appVersionName (versionCode $appVersionCode)"

android {
    namespace = "dev.iruki.classtime"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.iruki.classtime"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "dev.iruki.classtime.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }

        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
        if (signingReady) {
            create("shared") {
                storeFile = ksFile
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (signingReady) signingConfig = signingConfigs.getByName("shared")

            firebaseAppDistribution {
                artifactType = "APK"
                firebaseAppIdValue?.let { appId = it }
                firebaseCredentialsValue?.let { serviceCredentialsFile = it }
                firebaseTestersValue?.let { testers = it }
                firebaseGroupsValue?.let { groups = it }
                releaseNotes = firebaseReleaseNotesValue
            }
        }

        release {
            if (signingReady) signingConfig = signingConfigs.getByName("shared")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            firebaseAppDistribution {
                artifactType = "APK"
                firebaseAppIdValue?.let { appId = it }
                firebaseCredentialsValue?.let { serviceCredentialsFile = it }
                firebaseTestersValue?.let { testers = it }
                firebaseGroupsValue?.let { groups = it }
                releaseNotes = firebaseReleaseNotesValue
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        // AppLog 가 BuildConfig.DEBUG 로 릴리스 로그를 지우는 데 필요하다.
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // MigrationTestHelper 가 실기기에서 스키마 JSON 을 읽을 수 있도록 assets 에 넣는다.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// --- Deployment helper tasks ---------------------------------------------------

tasks.register("bumpVersionCode") {
    group = "versioning"
    description = "Increments versionCode in version.properties so the next build can install over the previous one."

    val propsFile = versionPropsFile
    val currentName = appVersionName

    doLast {
        val current = loadProps(propsFile).getProperty("versionCode").orNullIfBlank()?.toInt() ?: 0
        val next = current + 1
        propsFile.writeText(
            """
            # Release version of the app.
            #
            # versionCode MUST increase on every build you distribute: Android refuses to
            # install an update whose versionCode is not greater than the installed one.
            # `./gradlew bumpVersionCode` (run automatically by ./deploy.sh) increments it.
            # versionName is the human-readable label; edit it by hand whenever you like.
            #
            # This file is committed on purpose so the counter is shared and never rewinds.
            versionCode=$next
            versionName=$currentName
            """.trimIndent() + "\n"
        )
        logger.quiet("versionCode: $current -> $next (versionName $currentName)")
    }
}

tasks.register("deployConfigCheck") {
    group = "verification"
    description = "Reports which signing / Firebase App Distribution settings are resolved and which are missing."

    val report = buildList {
        add("versionCode" to appVersionCode.toString())
        add("versionName" to appVersionName)
        add("keystore file" to (ksFile?.absolutePath ?: "<missing>"))
        add("keystore exists" to (ksFile?.isFile == true).toString())
        add("keystore password" to if (ksStorePassword != null) "<set>" else "<missing>")
        add("key alias" to ksKeyAlias)
        add("key password" to if (ksKeyPassword != null) "<set>" else "<missing>")
        add("fixed signing active" to signingReady.toString())
        add("firebase appId" to (firebaseAppIdValue ?: "<missing>"))
        add("firebase auth" to (firebaseAuthMethod ?: "<missing>"))
        if (firebaseCredentialsValue != null) {
            add("  credentials file" to firebaseCredentialsValue)
        }
        add("firebase testers" to (firebaseTestersValue ?: "<none>"))
        add("firebase groups" to (firebaseGroupsValue ?: "<none>"))
    }
    val problems = buildList {
        if (!signingReady) {
            add(
                "No fixed signing key. Set CLASSTIME_KEYSTORE_FILE / CLASSTIME_KEYSTORE_PASSWORD / " +
                    "CLASSTIME_KEY_ALIAS / CLASSTIME_KEY_PASSWORD, or fill in keystore.properties " +
                    "(copy keystore.properties.example). Create the keystore with ./scripts/create-keystore.sh."
            )
        }
        if (firebaseAppIdValue == null) {
            add(
                "No Firebase app ID. Set FIREBASE_APP_ID (or the firebaseAppId property). " +
                    "Once signed in, `firebase apps:list ANDROID` prints it (the argument is the " +
                    "PLATFORM, not the project id); it looks like 1:123456789012:android:abc123. " +
                    "That command needs an active project: keep a .firebaserc next to this build " +
                    "file, or pass --project <project-id>. If no Android app is registered yet, " +
                    "create one with `firebase apps:create ANDROID ClassTime -a dev.iruki.classtime`."
            )
        }
        if (firebaseAuthMethod == null) {
            add(
                "Not authenticated with Firebase. On this machine: run " +
                    "`firebase login --no-localhost` - the Gradle plugin picks the CLI's stored " +
                    "credentials up automatically, no service account or JSON key needed. " +
                    "CI has no browser and no such cache, so it needs one of: FIREBASE_TOKEN " +
                    "(from `firebase login:ci`), or GOOGLE_APPLICATION_CREDENTIALS pointing at a " +
                    "service account JSON with the Firebase App Distribution Admin role."
            )
        } else if (firebaseCredentialsValue != null && !resolvePath(firebaseCredentialsValue).isFile) {
            add("Service account JSON not found at: $firebaseCredentialsValue")
        }
        if (firebaseTestersValue == null && firebaseGroupsValue == null) {
            add(
                "No testers or groups. Set FIREBASE_TESTERS (comma-separated emails) and/or " +
                    "FIREBASE_GROUPS (comma-separated group aliases), otherwise the upload succeeds " +
                    "but nobody is notified."
            )
        }
    }

    doLast {
        logger.quiet("ClassTime deployment configuration")
        logger.quiet("-".repeat(60))
        report.forEach { (k, v) -> logger.quiet("  %-22s %s".format(k, v)) }
        if (problems.isEmpty()) {
            logger.quiet("-".repeat(60))
            logger.quiet("All set - ./deploy.sh can build and upload.")
        } else {
            logger.quiet("-".repeat(60))
            problems.forEach { logger.quiet("  [!] $it") }
            throw GradleException("${problems.size} deployment setting(s) missing - see the list above.")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // 계측 테스트(실기기/에뮬레이터). 로컬 컨테이너에는 KVM 이 없어 CI 에서 실행된다.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
