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
    private var streaming = false
    private var currentCanSpeed = 500
    private val rxBuffer = StringBuilder()
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
            text = "Подключи Arduino Nano через USB OTG"
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

        layout.addView(makeButton("1. Подключить Arduino") { findDevice() })
        startButton = makeButton("STOP") {
            if (streaming) stopCapture() else startCapture()
        }
        startButton.isEnabled = false
        layout.addView(startButton)
        layout.addView(makeButton("Отключить") {
            closePort()
            setStatus("Отключено")
        })

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

            // The Nano firmware starts CAN reception automatically after initialization.
            streaming = true
            startButton.text = "STOP"
            startButton.isEnabled = true
            speedStatus.text = "Скорость CAN: ${currentCanSpeed} кбит/с"
            setStatus("Arduino подключена • автоматический приём CAN")
        } catch (e: Exception) {
            setStatus("Ошибка: ${e.message}")
        }
    }

    private fun handleLine(line: String) {
        when {
            line == "STARTED" -> {
                streaming = true
                startButton.text = "STOP"
                startButton.isEnabled = true
                setStatus("Приём CAN запущен")
            }
            line == "STOPPED" -> {
                streaming = false
                startButton.text = "START"
                startButton.isEnabled = true
                setStatus("Приём CAN остановлен")
            }
            line.startsWith("CAN_SPEED=") -> {
                currentCanSpeed = line.substringAfter('=').toIntOrNull() ?: currentCanSpeed
                speedStatus.text = "Скорость CAN: ${currentCanSpeed} кбит/с"
            }
            line.startsWith("ERR") -> setStatus("Arduino: $line")
            line == "READY" -> setStatus("Arduino готова")
            line.startsWith("ID=") -> setStatus("CAN: $line")
        }
    }

    private fun startCapture() = sendCommand("START\n")
    private fun stopCapture() = sendCommand("STOP\n")

    private fun sendCommand(command: String) {
        val activePort = port
        if (activePort == null) {
            setStatus("Сначала нажми «Подключить Arduino»")
            return
        }
        try {
            // Update the toggle immediately; do not depend on a possibly delayed serial reply.
            streaming = command.startsWith("START")
            startButton.text = if (streaming) "STOP" else "START"
            activePort.write(command.toByteArray(Charsets.US_ASCII), 1000)
            setStatus(if (streaming) "Запуск приёма CAN…" else "Остановка приёма CAN…")
        } catch (e: Exception) {
            setStatus("Ошибка отправки: ${e.message}")
        }
    }

    private fun closePort() {
        streaming = false
        if (::startButton.isInitialized) {
            startButton.text = "START"
            startButton.isEnabled = false
        }
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