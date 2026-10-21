package com.wanderwildwood.shoka

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Reads an ISBN printed as text, with Tesseract, on the phone. The model ships in the APK and
 * is copied out to the app's own storage the first time, since Tesseract reads it from a file.
 * One thread at a time; [close] frees the native engine.
 */
class NumberReader(context: Context) {
    private val app = context.applicationContext
    private var tess: TessBaseAPI? = null
    private var broken = false

    /** What Tesseract reads in [grey], made black on white first, as it reads it. */
    fun text(grey: Grey): String {
        val t = engine() ?: return ""
        val ink = Band.ink(grey)
        t.setImage(ink.pixels, ink.width, ink.height, 1, ink.width)
        val text = t.getUTF8Text() ?: ""
        t.clear()
        return text
    }

    /** The ISBN-13 in [grey], or null when there is none whose check digit is right. */
    fun read(grey: Grey): String? = PrintedIsbn.find(text(grey))

    fun close() {
        tess?.recycle()
        tess = null
    }

    private fun engine(): TessBaseAPI? {
        tess?.let { return it }
        if (broken) return null
        return runCatching {
            val root = File(app.filesDir, "tesseract")
            copyModel(File(root, "tessdata/$LANG.traineddata"))
            val t = TessBaseAPI()
            if (!t.init(root.absolutePath, LANG, TessBaseAPI.OEM_LSTM_ONLY)) {
                t.recycle()
                error("init")
            }
            t.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            t.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789X-ISBN")
            t
        }.onFailure { broken = true }.getOrNull().also { tess = it }
    }

    /** The model, written out when it is missing or differs from the one in this APK. */
    private fun copyModel(file: File) {
        val bytes = app.assets.open("tessdata/$LANG.traineddata").use { it.readBytes() }
        if (file.isFile && file.length() == bytes.size.toLong()) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(file)) error("rename")
    }

    private companion object {
        const val LANG = "eng"
    }
}
