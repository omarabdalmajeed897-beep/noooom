package com.compressor.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class CompressorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
