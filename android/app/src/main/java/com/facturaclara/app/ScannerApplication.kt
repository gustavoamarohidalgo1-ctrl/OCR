package com.facturaclara.app

import android.app.Application
import androidx.work.Configuration
import java.util.concurrent.Executors

class ScannerApplication : Application(), Configuration.Provider {
    // Serial OCR keeps a batch of 100 from loading many page bitmaps at once.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .setTaskExecutor(Executors.newFixedThreadPool(2))
            .build()

    override fun onCreate() {
        super.onCreate()
        Thread({
            val repository = Repository(this)
            repository.list().filter { it.status in setOf("queued", "processing") }
                .forEach { repository.enqueue(it.id) }
        }, "restore-invoice-queue").start()
    }
}
