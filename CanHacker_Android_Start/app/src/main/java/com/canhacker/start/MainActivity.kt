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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.media.AudioManager
import android.media.ToneGenerator
import android.widget.TextView
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var speedStatus: TextView
    private lateinit var speedSelector: Spinner
    private lateinit var connectButton: Button
    private lateinit var startButton: Button
    private lateinit var framesText: TextView
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connected = false
    private var receiving = false
    private val clickTone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }
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
            setPadding(20, 24, 20, 16)
        }
        status = TextView(this).apply {
            text = "Отключено"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }
        layout.addView(status, matchWrap())

        speedStatus = TextView(this).apply {
            text = "Скорость CAN: —"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        layout.addView(speedStatus, matchWrap())

        speedSelector = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto", "125", "250", "500", "1000")
            )
            setSelection(3)
            setOnTouchListener { _, _ -> clickTone.startTone(ToneGenerator.TONE_PROP_BEEP, 45); false }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (connected) setCanSpeed(selectedItem.toString())
                }
            }
        }
        layout.addView(TextView(this).apply { text = "Скорость CAN, кбит/с"; textSize = 15f }, matchWrap())
        layout.addView(speedSelector, matchWrap())

        connectButton = makeButton("Подключить") {
            if (connected) disconnect() else findDevice()
        }
        layout.addView(connectButton)

        startButton = makeButton("Старт") { toggleReceiving() }
        startButton.isEnabled = false
        layout.addView(startButton)

        val scroll = ScrollView(this)
        framesText = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(4, 8, 4, 8)
            text = ""
        }
        scroll.addView(framesText)
        layout.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(layout)
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setOnClickListener {
                clickTone.startTone(ToneGenerator.TONE_PROP_BEEP, 65)
                action()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6 }
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
            connected = true
            receiving = false
            connectButton.text = "Отключить"
            startButton.text = "Старт"
            startButton.isEnabled = true
            speedStatus.text = "Скорость CAN: ожидание ответа Arduino..."
            setStatus("USB подключён")
            startReader(newPort)
            setCanSpeed(speedSelector.selectedItem.toString())
        } catch (e: Exception) {
            disconnect()
            setStatus("Ошибка USB: ${e.message}")
        }
    }

    private fun startReader(serialPort: UsbSerialPort) {
        ioManager?.stop()
        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val lines = mutableListOf<String>()
                synchronized(rxBuffer) {
                    for (b in data) {
                        val c = b.toInt().toChar()
                        if (c == '\n' || c == '\r') {
                            if (rxBuffer.isNotEmpty()) {
                                lines.add(rxBuffer.toString())
                                rxBuffer.clear()
                            }
                        } else if (rxBuffer.length < 512) {
                            rxBuffer.append(c)
                        } else {
                            rxBuffer.clear()
                        }
                    }
                }
                if (lines.isNotEmpty()) runOnUiThread { lines.forEach { handleLine(it.trim()) } }
            }
            override fun onRunError(e: Exception) {
                runOnUiThread { setStatus("Ошибка порта: ${e.message}") }
            }
        }).also { it.start() }
    }

    private fun toggleReceiving() {
        val p = port ?: return
        try {
            if (receiving) {
                p.write("STOP\n".toByteArray(Charsets.US_ASCII), 1000)
                receiving = false
                startButton.text = "Старт"
                setStatus("Приём остановлен • USB подключён")
            } else {
                p.write("START\n".toByteArray(Charsets.US_ASCII), 1000)
                receiving = true
                startButton.text = "Стоп"
                setStatus("Приём CAN запущен")
            }
        } catch (e: Exception) {
            setStatus("Ошибка команды: ${e.message}")
        }
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("CAN_SPEED=", true) -> {
                val value = line.substringAfter('=').trim()
                speedStatus.text = "Скорость CAN: ${value} кбит/с"
            }
            line.equals("AUTO_SCAN_START", true) -> {
                speedStatus.text = "Скорость CAN: автоопределение..."
                setStatus("Arduino определяет скорость CAN")
            }
            line.equals("AUTO_SCAN_FAILED", true) -> {
                speedStatus.text = "Скорость CAN: не определена"
                setStatus("Автоопределение CAN не удалось")
            }
            line.startsWith("ERR", true) -> {
                setStatus("Arduino: $line")
                speedStatus.text = "Скорость CAN: ошибка настройки"
            }
            line.startsWith("ID=", true) -> {
                if (receiving) framesText.append(line + "\n")
            }
        }
    }

    private fun setCanSpeed(rate: String) {
        val p = port ?: return
        try {
            if (rate.equals("Auto", ignoreCase = true)) {
                p.write("CAN SPEED AUTO\n".toByteArray(Charsets.US_ASCII), 1000)
                speedStatus.text = "Скорость CAN: автоопределение..."
                setStatus("Запущено автоопределение скорости CAN")
            } else {
                p.write("CAN SPEED $rate\n".toByteArray(Charsets.US_ASCII), 1000)
                speedStatus.text = "Скорость CAN: настройка $rate кбит/с..."
            }
        } catch (e: Exception) {
            setStatus("Ошибка скорости CAN: ${e.message}")
        }
    }

    private fun disconnect() {
        try { port?.write("STOP\n".toByteArray(Charsets.US_ASCII), 300) } catch (_: Exception) {}
        receiving = false
        connected = false
        try { ioManager?.stop() } catch (_: Exception) {}
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        connectButton.text = "Подключить"
        startButton.text = "Старт"
        startButton.isEnabled = false
        speedStatus.text = "Скорость CAN: —"
        setStatus("Отключено")
    }

    private fun setStatus(message: String) { status.text = message }

    override fun onDestroy() {
        disconnect()
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
