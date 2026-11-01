package com.wanderwildwood.shoka.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.shoka.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/shoka"

/** What the APK carries that is not this app's own code, and the licence each project declares. */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("Tesseract OCR and its English model, via Tesseract4Android", "Google and the Tesseract authors, adaptech.cz", "Apache-2.0", "https://github.com/adaptech-cz/Tesseract4Android"),
    LicenceComponent("Leptonica (inside Tesseract4Android)", "Dan Bloomberg", "Leptonica", "https://www.leptonica.org"),
    LicenceComponent("libjpeg v9 (inside Tesseract4Android)", "Independent JPEG Group", "IJG", "https://libjpeg.sourceforge.net"),
    LicenceComponent("libpng (inside Tesseract4Android)", "Glenn Randers-Pehrson and contributors", "libpng-2.0", "https://www.libpng.org/pub/png/libpng.html"),
    LicenceComponent("zxing-cpp", "Nu-book Inc. and contributors", "Apache-2.0", "https://github.com/zxing-cpp/zxing-cpp"),
    LicenceComponent("CameraX", "Google", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/camera"),
    LicenceComponent("OkHttp and Okio", "Square", "Apache-2.0", "https://square.github.io/okhttp/"),
    LicenceComponent("Library of Congress catalogue (looked up, public domain)", "Library of Congress", "Public domain", "https://www.loc.gov"),
    LicenceComponent("Open Library (looked up)", "Internet Archive", "Public data", "https://openlibrary.org"),
    LicenceComponent("Mudita Mindful Design (MMD)", "Mudita", "Apache-2.0", "https://github.com/mudita/MMD"),
    LicenceComponent("Lato typeface, supplied with MMD", "\u0141ukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("AndroidX and Jetpack Compose", "Google", "Apache-2.0", "https://developer.android.com/jetpack"),
    LicenceComponent("Kotlin and kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://kotlinlang.org"),
    LicenceComponent("Material Symbols icons", "Google", "Apache-2.0", "https://fonts.google.com/icons"),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.gpl_3_0,
    "apache" to R.raw.apache_2_0,
    "ofl" to R.raw.ofl_1_1,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Shelf",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "apache" -> R.string.licences_full_apache
            "ofl" -> R.string.licences_full_ofl
            else -> R.string.licences_full_head
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_head),
        licenceHeading = stringResource(R.string.licences_licence_head),
        builtWithHeading = stringResource(R.string.licences_built_head),
        fullTextsHeading = stringResource(R.string.licences_full_head),
        noBrowser = { address -> none.replace("@@", address) },
    )
}
