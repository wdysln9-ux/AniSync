@file:Suppress("UnstableApiUsage")

import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.apollo)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Map unique integers to each architecture for dynamic version codes
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

abstract class RenameApksTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputApkFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outputApkFolder: DirectoryProperty

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val flavorSuffix: Property<String>

    @get:Input
    abstract val buildTypeName: Property<String>

    @get:Input
    abstract val fallbackVersionName: Property<String>

    @get:Internal
    abstract val apkTransformationRequest: Property<ArtifactTransformationRequest<RenameApksTask>>

    @TaskAction
    fun renameApks() {
        apkTransformationRequest.get().submit(this) { builtArtifact ->
            val sourceFile = builtArtifact.path.toFile()
            val abi =
                builtArtifact.filters
                    .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                    ?.identifier ?: "universal"
            val versionName = builtArtifact.versionName ?: fallbackVersionName.get()
            val renamedFileName =
                "${appName.get()}${flavorSuffix.get()}-v${versionName}-${abi}-${buildTypeName.get()}.apk"
            val outputFile = outputApkFolder.file(renamedFileName).get().asFile
            sourceFile.copyTo(outputFile, overwrite = true)
            outputFile
        }
    }
}

android {
    namespace = "com.anisync.android"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.anisync.android"
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        targetSdk = 37
        versionCode = 23
        versionName = "3.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resValue("string", "app_name", "AniSync")
    }

    // Strip AGP-injected dependency-metadata signing block so F-Droid's
    // Binaries reproducibility check accepts the upstream-signed APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Split configuration to create a separate APK for each ABI
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // Automatically handle Version Code math for ABI splits
    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                val abiFilter =
                    output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
                val baseAbiCode = abiCodes[abiFilter]
                if (baseAbiCode != null) {
                    // E.g., if versionCode is 1, arm64 (2) becomes 12.
                    // This ensures the device always prefers the optimized split over the universal APK.
                    output.versionCode.set((output.versionCode.get() ?: 0) * 10 + baseAbiCode)
                }
            }

            val channelFlavor = variant.productFlavors.firstOrNull { it.first == "channel" }?.second
            val flavorSuffixValue =
                channelFlavor
                    ?.takeIf { it != "stable" }
                    ?.let { "-$it" }
                    ?: ""
            val versionNameFallback = variant.outputs.firstOrNull()?.versionName?.orNull ?: "unknown"
            val variantNameTitle =
                variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

            val renameApksTask = tasks.register<RenameApksTask>("rename${variantNameTitle}Apks") {
                appName.set("AniSync")
                flavorSuffix.set(flavorSuffixValue)
                buildTypeName.set(variant.buildType)
                fallbackVersionName.set(versionNameFallback)
            }

            val renameRequest =
                variant.artifacts
                    .use(renameApksTask)
                    .wiredWithDirectories(
                        RenameApksTask::inputApkFolder,
                        RenameApksTask::outputApkFolder,
                    ).toTransformMany(SingleArtifact.APK)

            renameApksTask.configure {
                apkTransformationRequest.set(renameRequest)
            }
        }
    }

    buildTypes {
        release {
            // CloudStream loads extension APIs and plugin classes dynamically/reflection-based.
            // Keep the Release APK unminified, matching Debug runtime behavior.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "IS_DEBUG_BUILD", "false")
            // Keep the Release variant installable without repository secrets.
            // GitHub runner provides Gradle's default debug keystore.
            signingConfig = signingConfigs.getByName("debug")
            vcsInfo { include = false }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            resValue("string", "app_name", "AniSync Debug")
            buildConfigField("Boolean", "IS_DEBUG_BUILD", "true")
        }
    }

    // Set up Product Flavors
    flavorDimensions += "channel"

    productFlavors {
        create("stable") {
            dimension = "channel"
            isDefault = true
        }

        create("preview") {
            dimension = "channel"
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            // This renames the app on the phone's home screen so you can tell them apart!
            resValue("string", "app_name", "AniSync Preview")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Weblate owns the translated locales and they land incomplete by design, so a string
        // missing from one of them is never a defect in this repo.
        disable += "MissingTranslation"
        // Every layout here is a RemoteViews widget, and RemoteViews inflates a plain ImageView.
        // app:tint would resolve to nothing, so android:tint is the only one that paints.
        disable += "UseAppTint"
    }

    // Ships the exported Room schemas into the androidTest APK. Without them MigrationTestHelper
    // fails on FileNotFoundException before it ever reaches a migration, which is why the migration
    // tests have never actually guarded anything. They matter here more than in most projects,
    // because DatabaseModule still builds with fallbackToDestructiveMigration, so a missing
    // migration wipes user data instead of crashing.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.compose.material3)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)
    implementation(libs.apollo.runtime)
    implementation(libs.apollo.cache)
    implementation(libs.apollo.cache.sqlite)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    implementation(libs.materialkolor)
    implementation(libs.jsoup)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.analytics)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.core.remoteviews)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.reorderable)
    implementation(libs.okhttp)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.biometric.compose)

    // Bundled CloudStream API used to execute .cs3 extension packages natively.
    implementation("com.github.recloudstream.cloudstream:library:v4.8.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("uy.kohesive.injekt:injekt-core:1.16.1")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-zstd:5.5.0")
    compileOnly("com.github.Blatzar:NiceHttp:0.4.17")
    runtimeOnly("io.ktor:ktor-http:3.2.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.11.1")
    runtimeOnly("io.ktor:ktor-http:3.2.3")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.apollo.mockserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.leakcanary)
}

apollo {
    service("service") {
        packageName.set("com.anisync.android")
        introspection {
            endpointUrl.set("https://graphql.anilist.co")
            schemaFile.set(file("src/main/graphql/schema.graphqls"))
        }
        generateKotlinModels.set(true)
        // Generates the type policies, max ages and the cache() builder extension the normalized
        // cache reads.
        plugin(
            "com.apollographql.cache:normalized-cache-apollo-compiler-plugin:" +
                libs.versions.apolloCache.get()
        )
        pluginArgument("com.apollographql.cache.packageName", packageName.get())
        mapScalar("Json", "kotlin.Any", "com.apollographql.apollo.api.AnyAdapter")
        mapScalar("CountryCode", "kotlin.String", "com.apollographql.apollo.api.StringAdapter")
        mapScalar("FuzzyDateInt", "kotlin.Int", "com.apollographql.apollo.api.IntAdapter")
    }
}
