package com.example.lottery

import android.app.AlertDialog
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.util.Random

data class Student(
    val id: String,
    val name: String,
    val title: String,
    val uri: Uri,
    var isActive: Boolean = true,
    var isInCooldown: Boolean = false
)

sealed class HistoryItem {
    data class WinnerRecord(val winners: List<Student>) : HistoryItem()
    data class SystemRecord(val message: String) : HistoryItem()
}

class MainActivity : AppCompatActivity() {

    private val studentList = mutableListOf<Student>()
    private val historyList = mutableListOf<HistoryItem>()
    private val handler = Handler(Looper.getMainLooper())
    private var isRolling = false

    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    private var showId = true
    private var showName = true
    private var showTitle = false 
    private var batchCount = 1

    private lateinit var tvStatusInfo: TextView
    private lateinit var tvResultId: TextView
    private lateinit var tvResultName: TextView
    private lateinit var tvResultTitle: TextView
    private lateinit var tvHistoryLog: TextView
    private lateinit var imgResult: ImageView
    private lateinit var layoutMultiPhotos: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var btnResetCooldown: Button
    private lateinit var btnBatchCount: Button

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { parsePhotosFromDirectory(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusInfo = findViewById(R.id.tvStatusInfo)
        tvResultId = findViewById(R.id.tvResultId)
        tvResultName = findViewById(R.id.tvResultName)
        tvResultTitle = findViewById(R.id.tvResultTitle)
        tvHistoryLog = findViewById(R.id.tvHistoryLog)
        imgResult = findViewById(R.id.imgResult)
        layoutMultiPhotos = findViewById(R.id.layoutMultiPhotos)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnResetCooldown = findViewById(R.id.btnResetCooldown)
        btnBatchCount = findViewById(R.id.btnBatchCount)

        setupButtons()
    }

    private fun setupButtons() {
        btnSelectFolder.setOnClickListener { selectFolderLauncher.launch(null) }
        btnStart.setOnClickListener { startLottery() }
        btnStop.setOnClickListener { stopLottery() }
        btnResetCooldown.setOnClickListener { resetCooldown() }
        btnBatchCount.setOnClickListener { showBatchCountDialog() }

        tvStatusInfo.setOnLongClickListener {
            showSettingsDialog()
            true
        }
        
        tvStatusInfo.text = "状态: 未加载目录 (长按打开设置)"
    }

    private fun parsePhotosFromDirectory(treeUri: Uri) {
        studentList.clear()
        val rootFolder = DocumentFile.fromTreeUri(this, treeUri) ?: return
        
        for (file in rootFolder.listFiles()) {
            if (file.isFile && file.name?.endsWith(".png", ignoreCase = true) == true) {
                val fileName = file.name?.removeSuffix(".png") ?: continue
                val parts = fileName.split("_")

                val (id, name, title) = when {
                    parts.size >= 3 -> Triple(parts[0], parts[1], parts.subList(2, parts.size).joinToString("_"))
                    parts.size == 2 -> Triple(parts[0], parts[1], "")
                    else -> Triple(fileName, "同学", "")
                }

                studentList.add(Student(id, name, title, file.uri))
            }
        }
        updateStatusInfo()
    }

    private fun updateStatusInfo() {
        val total = studentList.size
        val available = studentList.count { it.isActive && !it.isInCooldown }
        val absent = studentList.count { !it.isActive }
        val cooled = studentList.count { it.isInCooldown }
        tvStatusInfo.text = "总:$total | 可抽:$available | 请假:$absent | 冷:$cooled"
    }

    private fun showBatchCountDialog() {
        val counts = arrayOf("1人 (单抽)", "2人", "3人", "4人", "5人")
        AlertDialog.Builder(this)
            .setTitle("选择每次抽取人数")
            .setItems(counts) { _, which ->
                batchCount = which + 1
                btnBatchCount.text = "抽取人数: ${batchCount}人"
            }
            .show()
    }

    private fun showSettingsDialog() {
        if (studentList.isEmpty()) {
            tvStatusInfo.text = "提示：请先选择照片目录！"
            return
        }

        val options = arrayOf("请假/出勤状态管理", "显示元素设置 (学号/姓名/职务)")
        AlertDialog.Builder(this)
            .setTitle("系统高级设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLeaveManagementDialog()
                    1 -> showDisplayConfigDialog()
                }
            }
            .show()
    }

    private fun showLeaveManagementDialog() {
        val names = studentList.map { "${it.id} - ${it.name}" }.toTypedArray()
        val checkedItems = studentList.map { it.isActive }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("请假管理 (勾选代表正常出勤)")
            .setMultiChoiceItems(names, checkedItems) { _, index, isChecked ->
                studentList[index].isActive = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                updateStatusInfo()
                historyList.add(0, HistoryItem.SystemRecord("系统: 更新了学生出勤状态"))
                refreshHistoryUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDisplayConfigDialog() {
        val items = arrayOf("显示学号", "显示姓名", "显示职务")
        val checked = booleanArrayOf(showId, showName, showTitle)

        AlertDialog.Builder(this)
            .setTitle("抽取结果显示元素设置")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> showId = isChecked
                    1 -> showName = isChecked
                    2 -> showTitle = isChecked
                }
            }
            .setPositiveButton("确定") { _, _ ->
                updateStatusInfo()
                refreshHistoryUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startLottery() {
        val available = studentList.filter { it.isActive && !it.isInCooldown }
        if (available.isEmpty()) {
            tvStatusInfo.text = "提示：没有可抽取的对象了！"
            return
        }

        isRolling = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        rollRunnable.run()
    }

    private fun stopLottery() {
        isRolling = false
        handler.removeCallbacks(rollRunnable)

        toneGen.startTone(ToneGenerator.TONE_PROP_ACK)

        val available = studentList.filter { it.isActive && !it.isInCooldown }
        if (available.isNotEmpty()) {
            val actualCount = minOf(batchCount, available.size)
            val winners = available.shuffled().take(actualCount)
            winners.forEach { it.isInCooldown = true }

            displayBatchStudents(winners)

            historyList.add(0, HistoryItem.WinnerRecord(winners))
            refreshHistoryUI()
        }

        updateStatusInfo()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    private fun resetCooldown() {
        studentList.forEach { it.isInCooldown = false }
        updateStatusInfo()
        historyList.add(0, HistoryItem.SystemRecord("系统: 冷却状态已重置"))
        refreshHistoryUI()
    }

    private fun refreshHistoryUI() {
        if (historyList.isEmpty()) {
            tvHistoryLog.text = "暂无记录..."
            return
        }

        val formattedLogs = historyList.map { item ->
            when (item) {
                is HistoryItem.SystemRecord -> item.message
                is HistoryItem.WinnerRecord -> {
                    val namesStr = item.winners.joinToString("、") { s ->
                        val parts = mutableListOf<String>()
                        if (showId) parts.add(s.id)
                        if (showName) parts.add(s.name)
                        if (showTitle && s.title.isNotEmpty()) parts.add("(${s.title})")
                        parts.joinToString(" ")
                    }
                    "中选 (${item.winners.size}人): $namesStr"
                }
            }
        }
        tvHistoryLog.text = formattedLogs.joinToString("\n")
    }

    private val rollRunnable = object : Runnable {
        override fun run() {
            if (isRolling) {
                val available = studentList.filter { it.isActive && !it.isInCooldown }
                if (available.isNotEmpty()) {
                    if (batchCount == 1) {
                        val randomStudent = available[Random().nextInt(available.size)]
                        displaySingleStudent(randomStudent)
                    } else {
                        val rollingWinners = available.shuffled().take(batchCount)
                        displayBatchStudents(rollingWinners)
                    }
                }
                handler.postDelayed(this, 80)
            }
        }
    }

    private fun displaySingleStudent(student: Student) {
        imgResult.visibility = View.VISIBLE
        layoutMultiPhotos.visibility = View.GONE

        tvResultId.text = if (showId) "学号: ${student.id}" else ""
        tvResultName.text = if (showName) "姓名: ${student.name}" else ""
        tvResultTitle.text = if (showTitle && student.title.isNotEmpty()) "职务: ${student.title}" else if(showTitle) "职务: 无" else ""
        imgResult.setImageURI(student.uri)
    }

    private fun displayBatchStudents(winners: List<Student>) {
        if (winners.size == 1) {
            displaySingleStudent(winners[0])
            return
        }

        imgResult.visibility = View.GONE
        layoutMultiPhotos.visibility = View.VISIBLE
        layoutMultiPhotos.removeAllViews()

        val screenWidthDp = resources.displayMetrics.widthPixels
        val marginPx = (4 * resources.displayMetrics.density).toInt()
        val availableWidth = screenWidthDp - (marginPx * 2 * winners.size)
        val itemWidth = minOf(availableWidth / winners.size, (150 * resources.displayMetrics.density).toInt())
        val itemHeight = (220 * resources.displayMetrics.density).toInt()

        for (winner in winners) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(itemWidth, itemHeight).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(winner.uri)
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            layoutMultiPhotos.addView(iv)
        }

        val ids = winners.joinToString(", ") { it.id }
        val names = winners.joinToString(", ") { it.name }
        
        tvResultId.text = if (showId) "学号: $ids" else ""
        tvResultName.text = if (showName) "中选 (${winners.size}人)" else ""
        tvResultTitle.text = if (showTitle) "多人分组抽取" else ""
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGen.release()
    }
}
