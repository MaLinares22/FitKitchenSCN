package com.uth.fitkitchenscn

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.uth.fitkitchenscn.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEmpezar.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.btnSalir.setOnClickListener {
            finishAffinity()
        }
    }
}
