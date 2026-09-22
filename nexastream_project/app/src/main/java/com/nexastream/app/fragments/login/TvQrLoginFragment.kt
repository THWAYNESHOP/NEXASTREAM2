package com.nexastream.app.fragments.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nexastream.app.R
import com.nexastream.app.databinding.FragmentTvQrLoginBinding
import com.nexastream.app.utils.BypassWebSocketEndpointHelper
import com.nexastream.app.utils.QrUtils
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.UUID

class TvQrLoginFragment : Fragment() {

    private var _binding: FragmentTvQrLoginBinding? = null
    private val binding get() = _binding!!

    private var server: LoginWebSocketServer? = null
    private val port = 8082

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvQrLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupLogin()
    }

    private fun setupLogin() {
        val deviceId = UUID.randomUUID().toString()
        val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(port)

        if (wsUrl == null) {
            binding.tvStatus.text = getString(R.string.loading_error_title)
            return
        }

        val qrContent = "nexastream://login?ws=${wsUrl}&id=$deviceId"
        val bitmap = QrUtils.generate(qrContent, 600)
        binding.ivQrCode.setImageBitmap(bitmap)
        
        binding.tvStatus.text = getString(R.string.qr_login_waiting)
        binding.pbLoading.visibility = View.VISIBLE

        startServer(deviceId)
    }

    private fun startServer(deviceId: String) {
        server = LoginWebSocketServer(port, deviceId) { token ->
            viewLifecycleOwner.lifecycleScope.launch {
                onLoginSuccess(token)
            }
        }
        server?.start()
    }

    private fun onLoginSuccess(token: String) {
        UserPreferences.loginToken = token
        binding.tvStatus.text = getString(R.string.qr_login_success)
        binding.pbLoading.visibility = View.GONE
        Toast.makeText(requireContext(), R.string.qr_login_success, Toast.LENGTH_SHORT).show()
        
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1500)
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            server?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _binding = null
    }

    private class LoginWebSocketServer(
        port: Int,
        private val expectedDeviceId: String,
        private val onSuccess: (String) -> Unit
    ) : WebSocketServer(InetSocketAddress(port)) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            android.util.Log.d("QrLogin", "WebSocket connection opened")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            android.util.Log.d("QrLogin", "Message received: $message")
            // Expected format: "login:deviceId:token"
            if (message.startsWith("login:")) {
                val parts = message.split(":")
                if (parts.size == 3 && parts[1] == expectedDeviceId) {
                    val token = parts[2]
                    onSuccess(token)
                    conn.send("ack")
                } else {
                    conn.send("error:invalid_pairing")
                }
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onError(conn: WebSocket?, ex: Exception) {
            ex.printStackTrace()
        }
        override fun onStart() {
            android.util.Log.d("QrLogin", "WebSocket server started on port $port")
        }
    }
}
