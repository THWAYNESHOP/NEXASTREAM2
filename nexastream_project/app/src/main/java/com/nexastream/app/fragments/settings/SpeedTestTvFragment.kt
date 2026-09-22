package com.nexastream.app.fragments.settings

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nexastream.app.databinding.FragmentSpeedTestTvBinding
import com.nexastream.app.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.Locale

class SpeedTestTvFragment : Fragment() {

    private var _binding: FragmentSpeedTestTvBinding? = null
    private val binding get() = _binding!!
    
    private var testJob: Job? = null
    private val testUrl = "https://speed.cloudflare.com/__down?bytes=10000000" // 10MB test file

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSpeedTestTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnStartTest.setOnClickListener {
            if (testJob?.isActive == true) {
                stopTest()
            } else {
                startTest()
            }
        }
        
        binding.btnStartTest.requestFocus()
    }

    private fun startTest() {
        testJob?.cancel()
        resetUi()
        
        binding.btnStartTest.text = "Stop Test"
        binding.tvTestStatus.text = "Connecting..."
        
        testJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val startTime = SystemClock.elapsedRealtime()
                val request = Request.Builder().url(testUrl).build()
                
                withContext(Dispatchers.IO) {
                    NetworkClient.minimal.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")
                        
                        val body = response.body ?: throw IOException("Empty response body")
                        val contentLength = body.contentLength()
                        val inputStream = body.byteStream()
                        
                        val buffer = ByteArray(8192)
                        var totalBytesRead: Long = 0
                        val updateInterval = 200L // Update UI every 200ms
                        var lastUpdate = SystemClock.elapsedRealtime()

                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read == -1) break
                            
                            totalBytesRead += read
                            val currentTime = SystemClock.elapsedRealtime()
                            
                            if (currentTime - lastUpdate >= updateInterval) {
                                val timeDiff = currentTime - startTime
                                if (timeDiff > 0) {
                                    val speedMbps = (totalBytesRead * 8.0 / (timeDiff * 1000.0))
                                    withContext(Dispatchers.Main) {
                                        updateProgress(speedMbps, totalBytesRead)
                                    }
                                }
                                lastUpdate = currentTime
                            }
                        }
                        
                        val finalTime = SystemClock.elapsedRealtime() - startTime
                        val finalSpeedMbps = (totalBytesRead * 8.0 / (finalTime * 1000.0))
                        withContext(Dispatchers.Main) {
                            finalizeTest(finalSpeedMbps, totalBytesRead)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvTestStatus.text = "Error: ${e.message}"
                    binding.btnStartTest.text = "Start Test"
                }
            }
        }
    }

    private fun updateProgress(speedMbps: Double, bytesRead: Long) {
        binding.tvCurrentSpeed.text = String.format(Locale.ROOT, "%.1f", speedMbps)
        binding.pbSpeedGauge.progress = (speedMbps * 10).toInt().coerceAtMost(1000)
        binding.tvTestStatus.text = "Testing..."
        binding.tvDetailData.text = String.format(Locale.ROOT, "Data: %.1f MB", bytesRead / (1024.0 * 1024.0))
    }

    private fun finalizeTest(speedMbps: Double, totalBytes: Long) {
        binding.tvCurrentSpeed.text = String.format(Locale.ROOT, "%.1f", speedMbps)
        binding.pbSpeedGauge.progress = (speedMbps * 10).toInt().coerceAtMost(1000)
        binding.tvTestStatus.text = "Test Completed"
        binding.btnStartTest.text = "Restart Test"
        binding.tvDetailData.text = String.format(Locale.ROOT, "Data: %.1f MB", totalBytes / (1024.0 * 1024.0))
    }

    private fun stopTest() {
        testJob?.cancel()
        binding.tvTestStatus.text = "Test Stopped"
        binding.btnStartTest.text = "Start Test"
    }

    private fun resetUi() {
        binding.tvCurrentSpeed.text = "0.0"
        binding.pbSpeedGauge.progress = 0
        binding.tvDetailData.text = "Data: 0 MB"
        binding.tvDetailLatency.text = "Latency: -- ms"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        testJob?.cancel()
        _binding = null
    }
}
