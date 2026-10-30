package com.example.lottery

import android.app.AlertDialog
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
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
    var isActive: Boolean = true,       // 是否参与抽取（请假开关）
    var isInCooldown: Boolean = false   // 冷却状态
)

// 定义历史记录的存储结构：保存学生对象和事件标签
sealed class HistoryItem {
    data class WinnerRecord(val student: Student) : HistoryItem()
    data class SystemRecord(val message: String) : HistoryItem()
}

class MainActivity : AppCompatActivity() {

    private val studentList = mutableListOf<Student>()
    private val historyList = mutableListOf<HistoryItem>() // 改为存储结构化数据
    private val handler = Handler(Looper.getMainLooper())
    private var isRolling = false

    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    // 显示元素配置项
    private var showId = true
    private var showName = true
    private var showTitle = true

    // UI 控件
    private lateinit var tvStatusInfo: TextView
    private lateinit var tvResultId: TextView
    private lateinit var tvResultName: TextView
    private lateinit var tvResultTitle: TextView
    private lateinit var tvHistoryLog: TextView
    private lateinit var imgResult: ImageView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var btnResetCooldown: Button

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
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnResetCooldown = findViewById(R.id.btnResetCooldown)

        setupButtons()
    }

    private val setupButtons = {
        btnSelectFolder.setOnClickListener { selectFolderLauncher.launch(null) }
        btnStart.setOnClickListener { startLottery() }
        btnStop.setOnClickListener { stopLottery() }
        btnResetCooldown.setOnClickListener { resetCooldown() }

        tvStatusInfo.setOnLongClickListener {
            showSettingsDialog()
            true
        }
        
        tvStatusInfo.text = "状态: 未加载目录 (💡长按本栏打开设置)"
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
        tvStatusInfo.text = "总:$total | 可抽:$available | 请假:$absent | 冷:$cooled (💡长按打开设置)"
    }

    private fun showSettingsDialog() {
        if (studentList.isEmpty()) {
            tvStatusInfo.text = "提示：请先选择照片目录加载学生名单！"
            return
        }

        val options = arrayOf("👥 请假/出勤状态管理", "🎨 显示元素设置 (学号/姓名/职务)")
        AlertDialog.Builder(this)
            .setTitle("⚙ 系统高级设置")
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
            .setTitle("👥 请假管理 (勾选代表正常出勤)")
            .setMultiChoiceItems(names, checkedItems) { _, index, isChecked ->
                studentList[index].isActive = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                updateStatusInfo()
                historyList.add(0, HistoryItem.SystemRecord("【系统】更新了学生出勤状态"))
                refreshHistoryUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDisplayConfigDialog() {
        val items = arrayOf("显示学号", "显示姓名", "显示职务")
        val checked = booleanArrayOf(showId, showName, showTitle)

        AlertDialog.Builder(this)
            .setTitle("🎨 抽取结果显示元素设置")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> showId = isChecked
                    1 -> showName = isChecked
                    2 -> showTitle = isChecked
                }
            }
            .setPositiveButton("确定") { _, _ ->
                updateStatusInfo()
                // 当设置改变时，立即全局刷新历史记录界面的渲染格式！
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
            val winner = available[Random().nextInt(available.size)]
            winner.isInCooldown = true
            displayStudent(winner)

            // 将结构化的中选记录加入列表
            historyList.add(0, HistoryItem.WinnerRecord(winner))
            refreshHistoryUI()
        }

        updateStatusInfo()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    private fun resetCooldown() {
        studentList.forEach { it.isInCooldown = false }
        updateStatusInfo()
        historyList.add(0, HistoryItem.SystemRecord("【系统】冷却状态已重置"))
        refreshHistoryUI()
    }

    // 核心方法：根据当前的 showId、showName、showTitle 实时渲染所有历史记录
    private fun refreshHistoryUI() {
        if (historyList.isEmpty()) {
            tvHistoryLog.text = "暂无记录..."
            return
        }

        val formattedLogs = historyList.map { item ->
            when (item) {
                is HistoryItem.SystemRecord -> item.message
                is HistoryItem.WinnerRecord -> {
                    val s = item.student
                    val parts = mutableListOf<String>()
                    if (showId) parts.add(s.id)
                    if (showName) parts.add(s.name)
                    if (showTitle && s.title.isNotEmpty()) parts.add("(${s.title})")
                    
                    val recordStr = parts.joinToString(" ")
                    "【中选】$recordStr"
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
                    val randomStudent = available[Random().nextInt(available.size)]
                    displayStudent(randomStudent)
                }
                handler.postDelayed(this, 80)
            }
        }
    }

    private fun displayStudent(student: Student) {
        tvResultId.text = if (showId) "学号: ${student.id}" else ""
        tvResultName.text = if (showName) "姓名: ${student.name}" else ""
        tvResultTitle.text = if (showTitle && student.title.isNotEmpty()) "职务: ${student.title}" else if(showTitle) "职务: 无" else ""
        imgResult.setImageURI(student.uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGen.release()
    }
}
