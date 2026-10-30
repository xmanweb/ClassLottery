package com.example.lottery

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
    var isActive: Boolean = true,
    var isInCooldown: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private val studentList = mutableListOf<Student>()
    private val historyList = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var isRolling = false

    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

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

        btnSelectFolder.setOnClickListener { selectFolderLauncher.launch(null) }
        btnStart.setOnClickListener { startLottery() }
        btnStop.setOnClickListener { stopLottery() }
        btnResetCooldown.setOnClickListener { resetCooldown() }
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
        val cooled = studentList.count { it.isInCooldown }
        tvStatusInfo.text = "总人数: $total | 可抽取: $available | 已冷却: $cooled"
    }

    private fun startLottery() {
        val available = studentList.filter { it.isActive && !it.isInCooldown }
        if (available.isEmpty()) {
            tvStatusInfo.text = "提示：没有可抽取的对象了，请先重置冷却！"
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

            // 写入历史记录
            val record = "【中选】${winner.id} - ${winner.name} ${if(winner.title.isNotEmpty()) "(${winner.title})" else ""}"
            historyList.add(0, record)
            tvHistoryLog.text = historyList.joinToString("\n")
        }

        updateStatusInfo()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    private fun resetCooldown() {
        studentList.forEach { it.isInCooldown = false }
        updateStatusInfo()
        tvHistoryLog.text = "--- 冷却状态已重置 ---"
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
        tvResultId.text = "学号: ${student.id}"
        tvResultName.text = "姓名: ${student.name}"
        tvResultTitle.text = if (student.title.isNotEmpty()) "职务: ${student.title}" else "职务: 无"
        imgResult.setImageURI(student.uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGen.release()
    }
}
