package com.canhacker.start

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var speedStatus: TextView
    private lateinit var startButton: Button
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connected = false
    private var currentCanSpeed: Int? = null
    private val rxBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private val actionUsbPermission = "com.canhacker.start.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionUsbPermission) {
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    openDevice(device)
                } else {
                    setStatus("Нет разрешения на USB")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 36, 24, 24)
        }
        status = TextView(this).apply {
            text = "Нажми START для подключения Arduino Nano"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        speedStatus = TextView(this).apply {
            text = "Скорость CAN: —"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(speedStatus, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        startButton = makeButton("START") {
            if (connected) stopConnection() else findDevice()
        }
        layout.addView(startButton)

        setContentView(layout)
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 18f
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }

    private fun findDevice() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setStatus("USB-UART не найден. Проверь OTG и кабель.")
            return
        }
        val device = drivers[0].device
        if (!usbManager.hasPermission(device)) {
            val intent = PendingIntent.getBroadcast(
                this, 0, Intent(actionUsbPermission),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, intent)
        } else {
            openDevice(device)
        }
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .firstOrNull { it.device.deviceId == device.deviceId }
                ?: run { setStatus("Драйвер USB-UART не найден"); return }

            closePort()
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                setStatus("Не удалось открыть USB")
                return
            }
            val newPort = driver.ports[0]
            newPort.open(connection)
            newPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = newPort
            synchronized(rxBuffer) { rxBuffer.clear() }

            ioManager = SerialInputOutputManager(newPort, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val completeLines = mutableListOf<String>()
                    synchronized(rxBuffer) {
                        for (byte in data) {
                            val char = byte.toInt().toChar()
                            if (char == '\n' || char == '\r') {
                                if (rxBuffer.isNotEmpty()) {
                                    completeLines.add(rxBuffer.toString())
                                    rxBuffer.clear()
                                }
                            } else if (rxBuffer.length < 512) {
                                rxBuffer.append(char)
                            } else {
                                rxBuffer.clear()
                            }
                        }
                    }
                    if (completeLines.isNotEmpty()) runOnUiThread {
                        for (line in completeLines) handleLine(line.trim())
                    }
                }
                override fun onRunError(e: Exception) {
                    runOnUiThread { setStatus("Ошибка порта: ${e.message}") }
                }
            }).also { it.start() }

            connected = true
            startButton.text = "STOP"
            startButton.isEnabled = true
            currentCanSpeed = null
            speedStatus.text = "Скорость CAN: определение..."
            setStatus("Arduino подключена • приём CAN активен")
            // As in the Python app, wait for the Nano to finish rebooting, then request 500 kbit/s.
            handler.postDelayed({
                try {
                    port?.write("CAN SPEED 500\n".toByteArray(Charsets.US_ASCII), 1000)
                } catch (e: Exception) {
                    setStatus("Ошибка команды скорости: ${e.message}")
                }
            }, 800)
        } catch (e: Exception) {
            closePort()
            setStatus("Ошибка: ${e.message}")
        }
    }

    private fun handleLine(line: String) {
        val upper = line.uppercase()
        when {
            upper.startsWith("CAN_SPEED=") || upper.startsWith("CAN_SPEED:") ||
                upper.startsWith("SPEED=") || upper.startsWith("BITRATE=") -> {
                val value = line.substringAfterAny("=", ":").trim()
                val numeric = value.toIntOrNull()
                currentCanSpeed = numeric
                speedStatus.text = if (numeric != null) {
                    "Скорость CAN: ${numeric} кбит/с"
                } else {
                    "Скорость CAN: $value"
                }
            }
            upper == "AUTO_SCAN_START" -> speedStatus.text = "Скорость CAN: автоопределение..."
            upper == "AUTO_SCAN_FAILED" -> speedStatus.text = "Скорость CAN: не определена"
            upper.startsWith("ERR") -> setStatus("Arduino: $line")
            upper.startsWith("ID=") && connected -> setStatus("CAN: $line")
        }
    }

    private fun String.substringAfterAny(vararg delimiters: String): String {
        val index = delimiters.map { indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return this
        return substring(index + 1)
    }

    private fun stopConnection() {
        closePort()
        speedStatus.text = "Скорость CAN: —"
        setStatus("Отключено")
    }

    private fun closePort() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        startButton.text = "START"
        startButton.isEnabled = true
        try { ioManager?.stop() } catch (_: Exception) {}
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        synchronized(rxBuffer) { rxBuffer.clear() }
    }

    private fun setStatus(message: String) { status.text = message }

    override fun onDestroy() {
        closePort()
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
