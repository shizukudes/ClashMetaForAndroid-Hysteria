plugins {
    kotlin("android")
    id("kotlinx-serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":common"))

    ksp(libs.kaidl.compiler)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.androidx.core)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    implementation(libs.kaidl.runtime)
    implementation(libs.rikkax.multiprocess)
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))

    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-nowarn", "-Xsuppress-version-warnings")
    }
}

afterEvaluate {
    android {
        libraryVariants.forEach {
            val variantName = it.name
            sourceSets[variantName].kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/$variantName/kotlin"))
            sourceSets[variantName].java.srcDir(layout.buildDirectory.dir("generated/ksp/$variantName/java"))
        }
    }
}