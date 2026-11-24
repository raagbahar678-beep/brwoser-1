import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The workflow passes -PcopyId=1, 2, 3 ... so every copy gets its own
// package name (com.example.myapp.c1, c2, ...) and its own launcher label (1, 2, 3 ...).
val copyId: String = (project.findProperty("copyId") as String?) ?: "1"

// ---- Launcher icon: draws the copy number (1, 2, 3 ...) as the app logo ----
// No image tools needed: the digits are drawn as vector strokes at build time.
val iconResDir: File = layout.buildDirectory.dir("generated/copyicon/res").get().asFile
run {
    val glyphs = mapOf(
        '0' to "M0,0 L2,0 L2,4 L0,4 Z",
        '1' to "M0.2,1 L1,0 L1,4 M0,4 L2,4",
        '2' to "M0,0 L2,0 L2,2 L0,2 L0,4 L2,4",
        '3' to "M0,0 L2,0 L2,4 L0,4 M0,2 L2,2",
        '4' to "M0,0 L0,2 L2,2 M2,0 L2,4",
        '5' to "M2,0 L0,0 L0,2 L2,2 L2,4 L0,4",
        '6' to "M2,0 L0,0 L0,4 L2,4 L2,2 L0,2",
        '7' to "M0,0 L2,0 L0.7,4",
        '8' to "M0,0 L2,0 L2,4 L0,4 Z M0,2 L2,2",
        '9' to "M2,2 L0,2 L0,0 L2,0 L2,4 L0,4"
    )
    var digits = copyId.filter { it in '0'..'9' }
    if (digits.isEmpty()) digits = "0"
    val n = digits.length
    val s = when (n) {
        1 -> 10.0
        2 -> 8.6
        3 -> 6.6
        else -> 5.2
    }
    val gap = 0.8 * s
    val total = n * 2 * s + (n - 1) * gap
    val startX = 54.0 - total / 2
    val topY = 54.0 - 2 * s

    val path = StringBuilder()
    for (i in 0 until n) {
        val ox = startX + i * (2 * s + gap)
        for (tok in glyphs.getValue(digits[i]).split(" ")) {
            if (tok == "Z") {
                path.append("Z ")
            } else {
                val xy = tok.substring(1).split(",")
                val x = xy[0].toDouble() * s + ox
                val y = xy[1].toDouble() * s + topY
                path.append(tok[0]).append(String.format(Locale.US, "%.2f,%.2f ", x, y))
            }
        }
    }

    // Background colour: a different colour for every number
    val hue = ((copyId.toIntOrNull() ?: 1) * 137.508) % 360.0
    val sat = 0.75
    val v = 0.55
    val c = v * sat
    val hp = hue / 60.0
    val xx = c * (1 - Math.abs(hp % 2 - 1))
    val rgb: List<Double> = when {
        hp < 1 -> listOf(c, xx, 0.0)
        hp < 2 -> listOf(xx, c, 0.0)
        hp < 3 -> listOf(0.0, c, xx)
        hp < 4 -> listOf(0.0, xx, c)
        hp < 5 -> listOf(xx, 0.0, c)
        else -> listOf(c, 0.0, xx)
    }
    val m = v - c
    val bgHex = String.format(
        "#%02X%02X%02X",
        Math.round((rgb[0] + m) * 255).toInt(),
        Math.round((rgb[1] + m) * 255).toInt(),
        Math.round((rgb[2] + m) * 255).toInt()
    )
    val strokeW = String.format(Locale.US, "%.2f", 0.45 * s)

    val drawableDir = File(iconResDir, "drawable")
    val mipmapDir = File(iconResDir, "mipmap-anydpi-v26")
    drawableDir.mkdirs()
    mipmapDir.mkdirs()

    File(drawableDir, "ic_launcher_foreground.xml").writeText(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
        "    android:width=\"108dp\" android:height=\"108dp\"\n" +
        "    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n" +
        "    <path\n" +
        "        android:pathData=\"" + path.toString().trim() + "\"\n" +
        "        android:strokeColor=\"#FFFFFF\"\n" +
        "        android:strokeWidth=\"" + strokeW + "\"\n" +
        "        android:strokeLineCap=\"round\"\n" +
        "        android:strokeLineJoin=\"round\" />\n" +
        "</vector>\n"
    )
    File(drawableDir, "ic_launcher_background.xml").writeText(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">\n" +
        "    <solid android:color=\"" + bgHex + "\" />\n" +
        "</shape>\n"
    )
    File(mipmapDir, "ic_launcher.xml").writeText(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
        "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n" +
        "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n" +
        "    <monochrome android:drawable=\"@drawable/ic_launcher_foreground\" />\n" +
        "</adaptive-icon>\n"
    )
}

android {
    namespace = "com.example.myapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapp.c" + copyId
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resValue("string", "app_name", copyId)
    }

    buildFeatures {
        resValues = true
    }

    sourceSets.getByName("main").res.srcDir(iconResDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
