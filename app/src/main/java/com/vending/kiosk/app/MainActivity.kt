package com.vending.kiosk.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vending.kiosk.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnVendingKiosk = findViewById<Button>(R.id.btnVendingKiosk)
        val btnVendingTester = findViewById<Button>(R.id.btnVendingTester)
        val btnVendingCalibrator = findViewById<Button>(R.id.btnVendingCalibrator)

        btnVendingKiosk.setOnClickListener {
            Toast.makeText(this, "Vending Kiosk: pendiente de implementacion", Toast.LENGTH_SHORT).show()
        }
        btnVendingTester.setOnClickListener {
            startActivity(Intent(this, VendingTesterActivity::class.java))
        }
        btnVendingCalibrator.setOnClickListener {
            startActivity(Intent(this, VendingCalibratorActivity::class.java))
        }
    }
}
