package com.nexastream.app.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nexastream.app.R
import com.nexastream.app.databinding.DialogParentalPinTvBinding

class ParentalPinTvDialog(
    context: Context,
    title: String,
    message: String,
    private val isVerificationMode: Boolean = true,
    private val onPinSubmitted: (String) -> String?
) : Dialog(context) {

    private val binding = DialogParentalPinTvBinding.inflate(LayoutInflater.from(context))
    private var currentPin = ""
    private val dots: List<ImageView> by lazy {
        listOf(binding.ivDot1, binding.ivDot2, binding.ivDot3, binding.ivDot4)
    }

    init {
        setContentView(binding.root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        binding.tvPinTitle.text = title
        binding.tvPinMessage.text = message

        setupGrid()
    }

    private fun setupGrid() {
        val clickListener = View.OnClickListener { v ->
            if (v is TextView && v.text.length == 1 && v.text[0].isDigit()) {
                appendDigit(v.text.toString())
            }
        }

        binding.btnPin0.setOnClickListener(clickListener)
        binding.btnPin1.setOnClickListener(clickListener)
        binding.btnPin2.setOnClickListener(clickListener)
        binding.btnPin3.setOnClickListener(clickListener)
        binding.btnPin4.setOnClickListener(clickListener)
        binding.btnPin5.setOnClickListener(clickListener)
        binding.btnPin6.setOnClickListener(clickListener)
        binding.btnPin7.setOnClickListener(clickListener)
        binding.btnPin8.setOnClickListener(clickListener)
        binding.btnPin9.setOnClickListener(clickListener)

        binding.btnPinClr.setOnClickListener {
            clearPin()
        }

        binding.btnPinOk.setOnClickListener {
            if (currentPin.length == 4) {
                val error = onPinSubmitted(currentPin)
                if (error == null) {
                    dismiss()
                } else {
                    showError(error)
                }
            } else {
                showError(context.getString(R.string.settings_parental_pin_too_short))
            }
        }
    }

    private fun appendDigit(digit: String) {
        if (currentPin.length < 4) {
            currentPin += digit
            updateDots()
            hideError()
            
            if (currentPin.length == 4 && isVerificationMode) {
                // Auto-submit in verification mode if desired, 
                // but let's wait for OK to be safe/consistent.
            }
        }
    }

    private fun clearPin() {
        currentPin = ""
        updateDots()
        hideError()
    }

    private fun updateDots() {
        dots.forEachIndexed { index, imageView ->
            if (index < currentPin.length) {
                imageView.imageTintList = ContextCompat.getColorStateList(context, android.R.color.white)
            } else {
                imageView.imageTintList = ContextCompat.getColorStateList(context, R.color.white)?.withAlpha(64)
            }
        }
    }

    fun showError(error: String) {
        binding.tvPinError.text = error
        binding.tvPinError.visibility = View.VISIBLE
        currentPin = ""
        updateDots()
    }

    private fun hideError() {
        binding.tvPinError.visibility = View.INVISIBLE
    }
}
