package com.example.splitterrr.ui.main

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.splitterrr.R
import com.example.splitterrr.databinding.ActivityMainBinding
import com.example.splitterrr.ui.expense.ExpenseListFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load default fragment (ExpenseListFragment)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ExpenseListFragment())
                .commit()
        }

        requestLocationPermission()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Location permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestLocationPermission() {
        try{
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }catch (e: Exception){
            e.printStackTrace()
        }
    }


}