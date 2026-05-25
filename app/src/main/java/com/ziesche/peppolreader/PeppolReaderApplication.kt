package com.ziesche.peppolreader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PeppolReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
