package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.vending.kiosk.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnVendingKiosk = findViewById<View>(R.id.btnVendingKiosk)
        val btnVendingTester = findViewById<View>(R.id.btnVendingTester)
        val btnVendingCalibrator = findViewById<View>(R.id.btnVendingCalibrator)

        btnVendingKiosk.setOnClickListener {
            startActivity(Intent(this, KioskLoginActivity::class.java))
        }
        btnVendingTester.setOnClickListener {
            startActivity(Intent(this, VendingTesterActivity::class.java))
        }
        btnVendingCalibrator.setOnClickListener {
            startActivity(Intent(this, VendingCalibratorActivity::class.java))
        }
    }
}
