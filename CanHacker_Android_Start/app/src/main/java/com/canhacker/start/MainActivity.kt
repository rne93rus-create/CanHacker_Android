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
import android.os.SystemClock
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.content.pm.ActivityInfo
import android.widget.*
import android.media.AudioManager
import android.media.ToneGenerator
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
    private lateinit var triggerText: TextView
    private lateinit var table: TableLayout
    private lateinit var historyTable: TableLayout
    private lateinit var historyTitle: TextView
    private lateinit var countText: TextView
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connected = false
    private var receiving = false
    private var startElapsedRealtime = 0L
    private var connectionElapsedRealtime = 0L
    private var rxCount = 0
    private var selectedId: String? = null
    private val frames = linkedMapOf<String, FrameInfo>()
    private val rowViews = mutableMapOf<String, TableRow>()
    private val previousData = mutableMapOf<String, List<String>>()
    private val changedIds = mutableSetOf<String>()
    private val blockNames = mutableMapOf<String, String>()
    private val clickTone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }
    private val rxBuffer = StringBuilder()
    private val actionUsbPermission = "com.canhacker.start.USB_PERMISSION"
    private val bg = Color.rgb(244, 247, 252)
    private val blue = Color.rgb(25, 98, 190)
    private val red = Color.rgb(190, 25, 35)
    private val lineColor = Color.rgb(210, 220, 235)

    private data class FrameInfo(
        val number: Int, val first: Long, var last: Long,
        var count: Int = 0, var data: List<String> = emptyList(),
        var hz: Double = 0.0, val history: MutableList<Pair<Long, List<String>>> = mutableListOf(),
        var changedAt: Long = 0L
    )

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionUsbPermission) {
                val device = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else { @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) openDevice(device)
                else setStatus("Нет разрешения на USB")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        window.statusBarColor = blue
        window.navigationBarColor = Color.rgb(22, 48, 78)
        window.decorView.systemUiVisibility = 0
        buildUi()
        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(permissionReceiver, filter) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(8, 6, 8, 6) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "CanHacker  1.3.24"; textSize = 21f; setTypeface(null, Typeface.BOLD); setTextColor(blue) }, LinearLayout.LayoutParams(0, -2, 1.2f))
        connectButton = button("Подключить") { if (connected) disconnect() else findDevice() }
        top.addView(connectButton, weighted(0.8f))
        startButton = button("Старт") { toggleReceiving() }.apply { isEnabled = false }
        top.addView(startButton, weighted(0.6f))
        triggerText = TextView(this).apply { text = "TRIG —"; textSize = 16f; setTextColor(red); gravity = Gravity.CENTER; setPadding(8, 4, 8, 4) }
        top.addView(triggerText, weighted(0.8f))
        top.addView(TextView(this).apply { text = "CAN"; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY) }, weighted(0.3f))
        speedSelector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Auto", "125", "250", "500", "1000"))
            setSelection(3)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { if (connected) setCanSpeed(selectedItem.toString()) }
            }
        }
        top.addView(speedSelector, weighted(0.55f))
        root.addView(top)

        val statusBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(4, 5, 4, 5) }
        status = TextView(this).apply { text = "Отключено"; textSize = 13f; setTextColor(Color.DKGRAY) }
        statusBar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        speedStatus = TextView(this).apply { text = "CAN: —"; textSize = 13f; setTextColor(blue) }
        statusBar.addView(speedStatus)
        countText = TextView(this).apply { text = "Кадров: 0"; textSize = 13f; setPadding(14, 0, 4, 0); setTextColor(Color.DKGRAY) }
        statusBar.addView(countText)
        root.addView(statusBar)

        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 2, 0, 0) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply { text = "Сообщения CAN"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(blue); setPadding(4, 4, 4, 4) })
        val tableScroll = HorizontalScrollView(this)
        val verticalScroll = ScrollView(this)
        table = TableLayout(this).apply { isStretchAllColumns = false }
        val headings = listOf("ID", "DLC", "Dif", "Name", "DLC", "Data", "Hz", "Frames", "Last fr")
        table.addView(makeRow(headings, true, 15f))
        verticalScroll.addView(table)
        tableScroll.addView(verticalScroll)
        left.addView(tableScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        main.addView(left, LinearLayout.LayoutParams(0, -1, 2.1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 0, 0, 0) }
        historyTitle = TextView(this).apply { text = "История ID —"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(blue); setPadding(4, 4, 4, 4) }
        right.addView(historyTitle)
        val historyScroll = ScrollView(this)
        historyTable = TableLayout(this)
        historyTable.addView(makeRow(listOf("№", "Time", "Data"), true))
        historyScroll.addView(historyTable)
        right.addView(historyScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        main.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun weighted(w: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { setMargins(3, 2, 3, 2) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; minHeight = 38; setPadding(5, 0, 5, 0)
        setOnClickListener { clickTone.startTone(ToneGenerator.TONE_PROP_BEEP, 50); action() }
    }
    private fun makeRow(values: List<String>, header: Boolean, fontSize: Float = 12f): TableRow {
        val row = TableRow(this)
        values.forEachIndexed { index, value ->
            val cell = TextView(this).apply {
                text = value; textSize = fontSize
                setTextColor(if (header) Color.WHITE else Color.rgb(25, 40, 60))
                if (header) { setBackgroundColor(blue); setTypeface(null, Typeface.BOLD) }
                else setBackgroundColor(Color.WHITE)
                setPadding(7, 7, 7, 7); gravity = Gravity.CENTER_VERTICAL
                minWidth = when (index) { 0 -> 35; 1 -> 65; 2 -> 40; 3 -> 75; 4 -> 35; 5 -> 155; 6 -> 65; 7 -> 70; else -> 110 }
            }
            row.addView(cell)
        }
        return row
    }

    private fun findDevice() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) { setStatus("USB-UART не найден. Проверь OTG и кабель."); return }
        val device = drivers[0].device
        if (!usbManager.hasPermission(device)) {
            val intent = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, intent)
        } else openDevice(device)
    }

    private fun openDevice(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull { it.device.deviceId == device.deviceId }
                ?: run { setStatus("Драйвер USB-UART не найден"); return }
            val connection = usbManager.openDevice(driver.device) ?: run { setStatus("Не удалось открыть USB"); return }
            val newPort = driver.ports[0]
            newPort.open(connection)
            newPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = newPort; connected = true; receiving = false
            connectButton.text = "Отключить"; startButton.text = "Старт"; startButton.isEnabled = true
            connectionElapsedRealtime = SystemClock.elapsedRealtime()
            synchronized(rxBuffer) { rxBuffer.clear() }
            startReader(newPort)
            setStatus("USB подключён")
            setCanSpeed(speedSelector.selectedItem.toString())
        } catch (e: Exception) { disconnect(); setStatus("Ошибка USB: ${e.message}") }
    }

    private fun startReader(serialPort: UsbSerialPort) {
        ioManager?.stop()
        ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val lines = mutableListOf<String>()
                synchronized(rxBuffer) {
                    for (b in data) {
                        val c = b.toInt().toChar()
                        if (c == '\n' || c == '\r') { if (rxBuffer.isNotEmpty()) { lines.add(rxBuffer.toString()); rxBuffer.clear() } }
                        else if (rxBuffer.length < 512) rxBuffer.append(c) else rxBuffer.clear()
                    }
                }
                if (lines.isNotEmpty()) runOnUiThread { lines.forEach { handleLine(it.trim()) } }
            }
            override fun onRunError(e: Exception) { runOnUiThread { setStatus("Ошибка порта: ${e.message}") } }
        }).also { it.start() }
    }

    private fun toggleReceiving() {
        val p = port ?: return
        try {
            if (receiving) {
                p.write("STOP\n".toByteArray(Charsets.US_ASCII), 1000)
                receiving = false; startButton.text = "Старт"; setStatus("Приём остановлен")
            } else {
                p.write("START\n".toByteArray(Charsets.US_ASCII), 1000)
                receiving = true; startElapsedRealtime = SystemClock.elapsedRealtime()
                triggerText.text = "TRIG —"; startButton.text = "Стоп"
                setStatus("Приём CAN запущен")
            }
        } catch (e: Exception) { setStatus("Ошибка команды: ${e.message}") }
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("CAN_SPEED=", true) -> speedStatus.text = "CAN: ${line.substringAfter('=').trim()} кбит/с"
            line == "AUTO_SCAN_START" -> speedStatus.text = "CAN: автоопределение..."
            line == "AUTO_SCAN_FAILED" -> { speedStatus.text = "CAN: не определена"; setStatus("Автоопределение не удалось") }
            line.startsWith("ERR", true) -> setStatus("Arduino: $line")
            line == "BUTTON_PRESS" -> if (receiving) {
                val stamp = SystemClock.elapsedRealtime()
                triggerText.text = "TRIG ${formatElapsed(stamp - startElapsedRealtime)}"
                clickTone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                status.text = "Нажатие внешней кнопки"
                frames.forEach { (id, frame) ->
                    frame.history.add(stamp to List(8) { "◇" })
                    if (frame.history.size > 500) repeat(100) { if (frame.history.isNotEmpty()) frame.history.removeAt(0) }
                    if (selectedId == id) renderHistory(id)
                }
            }
            else -> parseFrame(line)?.let { (id, data) -> if (receiving) acceptFrame(id, data) }
        }
    }

    private fun parseFrame(line: String): Pair<String, List<String>>? {
        val m = Regex("""ID\s*=\s*([0-9A-Fa-f]{1,8}).*?DLC\s*=\s*(\d+).*?DATA\s*=\s*([0-9A-Fa-f ]*)""", RegexOption.IGNORE_CASE).find(line) ?: return null
        val id = m.groupValues[1].uppercase()
        val dlc = m.groupValues[2].toIntOrNull() ?: return null
        val bytes = m.groupValues[3].trim().split(Regex("""\s+""")).filter { it.isNotBlank() }.map { it.uppercase().padStart(2, '0') }.take(8)
        if (dlc !in 0..8 || bytes.size != dlc) return null
        return id to bytes
    }

    private fun acceptFrame(id: String, data: List<String>) {
        val now = SystemClock.elapsedRealtime()
        val prev = previousData[id]
        val changed = prev != null && prev != data
        previousData[id] = data.toList()
        val item = frames[id] ?: FrameInfo(frames.size + 1, now, now).also { frames[id] = it }
        item.count++
        item.last = now
        item.data = data.toList()
        item.history.add(now to data.toList())
        if (item.history.size > 500) repeat(100) { if (item.history.isNotEmpty()) item.history.removeAt(0) }
        item.hz = if (now - item.first > 50) (item.count - 1) * 1000.0 / (now - item.first) else 0.0
        if (changed) { changedIds.add(id); item.changedAt = now }
        rxCount++
        countText.text = "Кадров: $rxCount"
        updateRow(id)
        if (!receiving && selectedId == id) renderHistory(id)
    }

    private fun updateRow(id: String) {
        val f = frames[id] ?: return
        val existing = rowViews[id]
        val elapsed = formatElapsed(f.last - connectionElapsedRealtime)
        val values = listOf(f.number.toString(), "0x$id", if (id in changedIds) "◆" else "",
            blockNames[id] ?: "", f.data.size.toString(), f.data.joinToString(" "),
            "%.1f Hz".format(f.hz), f.count.toString(), elapsed)
        val row = existing ?: makeRow(values, false, 15f).also { created ->
            created.setOnClickListener { selectedId = id; if (!receiving) renderHistory(id) }
            created.setOnLongClickListener { editBlockName(id); true }
            table.addView(created)
            rowViews[id] = created
        }
        values.forEachIndexed { index, value -> (row.getChildAt(index) as TextView).text = value }
        for (i in 0 until row.childCount) {
            val cell = row.getChildAt(i) as TextView
            cell.setBackgroundColor(if (id in changedIds) Color.rgb(255, 229, 229) else Color.WHITE)
            cell.setTextColor(if (id in changedIds && i == 2) red else Color.rgb(25, 40, 60))
        }
    }

    private fun editBlockName(id: String) {
        val input = EditText(this).apply { setText(blockNames[id] ?: ""); hint = "Имя блока" }
        android.app.AlertDialog.Builder(this).setTitle("ID 0x$id").setView(input)
            .setPositiveButton("Сохранить") { _, _ -> blockNames[id] = input.text.toString().trim(); updateRow(id) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun renderHistory(id: String) {
        selectedId = id
        historyTitle.text = "История ID: 0x$id"
        while (historyTable.childCount > 1) historyTable.removeViewAt(1)
        val f = frames[id] ?: return
        f.history.takeLast(200).forEachIndexed { index, entry ->
            val elapsed = formatElapsed(entry.first - connectionElapsedRealtime)
            historyTable.addView(makeRow(listOf((index + 1).toString(), elapsed, entry.second.joinToString(" ")), false))
        }
    }

    private fun setCanSpeed(rate: String) {
        val p = port ?: return
        try {
            val command = if (rate.equals("Auto", true)) "CAN SPEED AUTO\n" else "CAN SPEED $rate\n"
            p.write(command.toByteArray(Charsets.US_ASCII), 1000)
            speedStatus.text = if (rate == "Auto") "CAN: автоопределение..." else "CAN: настройка $rate кбит/с..."
        } catch (e: Exception) { setStatus("Ошибка скорости CAN: ${e.message}") }
    }

    private fun disconnect() {
        try { port?.write("STOP\n".toByteArray(Charsets.US_ASCII), 300) } catch (_: Exception) {}
        receiving = false; connected = false
        try { ioManager?.stop() } catch (_: Exception) {}
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        connectButton.text = "Подключить"; startButton.text = "Старт"; startButton.isEnabled = false
        speedStatus.text = "CAN: —"; setStatus("Отключено")
    }

    private fun formatElapsed(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        return "%02d:%02d.%03d".format(value / 60000, (value / 1000) % 60, value % 1000)
    }
    private fun setStatus(message: String) { status.text = message }
    override fun onDestroy() {
        disconnect()
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

}