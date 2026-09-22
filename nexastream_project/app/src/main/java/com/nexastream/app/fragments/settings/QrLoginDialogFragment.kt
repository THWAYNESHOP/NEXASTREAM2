package com.nexastream.app.fragments.settings

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.nexastream.app.R
import com.nexastream.app.databinding.DialogQrLoginBinding
import com.nexastream.app.utils.BypassWebSocketEndpointHelper
import com.nexastream.app.utils.QrUtils
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.UUID

class QrLoginDialogFragment : DialogFragment() {

    private var _binding: DialogQrLoginBinding? = null
    private val binding get() = _binding!!

    private var server: LoginWebSocketServer? = null
    private val port = 8082

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_AppCompat_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQrLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val deviceId = UUID.randomUUID().toString()
        val ip = BypassWebSocketEndpointHelper.getLocalIpv4Address()
        val wsUrl = BypassWebSocketEndpointHelper.getAdvertisedWsUrl(port)

        if (wsUrl == null) {
            Toast.makeText(requireContext(), "Unable to determine local IP", Toast.LENGTH_LONG).show()
            dismiss()
            return
        }

        val qrContent = "nexastream://login?ws=${wsUrl}&id=$deviceId"
        val bitmap = QrUtils.generate(qrContent, 600)
        binding.qrImage.setImageBitmap(bitmap)
        binding.ipInfo.text = "IP: $ip | Port: $port"

        startServer(deviceId)
    }

    private fun startServer(deviceId: String) {
        server = LoginWebSocketServer(port, deviceId) { token ->
            lifecycleScope.launch {
                UserPreferences.loginToken = token
                Toast.makeText(requireContext(), R.string.qr_login_success, Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
        server?.start()
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

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}

        override fun onMessage(conn: WebSocket, message: String) {
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
        override fun onError(conn: WebSocket?, ex: Exception) {}
        override fun onStart() {}
    }
}
