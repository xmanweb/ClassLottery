package com.example.lottery

import android.content.Intent
import android.media.ToneGenerator
import android.media.AudioManager
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

// 学生数据结构
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
    private val handler = Handler(Looper.getMainLooper())
    private var isRolling = false

    // 系统自带音效生成器（无需音频文件）
    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    // UI 控件
    private lateinit var tvResultId: TextView
    private lateinit var tvResultName: TextView
    private lateinit var tvResultTitle: TextView
    private lateinit var imgResult: ImageView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSelectFolder: Button

    // 系统 SAF 文件夹选择器注册
    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { parsePhotosFromDirectory(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化控件
        tvResultId = findViewById(R.id.tvResultId)
        tvResultName = findViewById(R.id.tvResultName)
        tvResultTitle = findViewById(R.id.tvResultTitle)
        imgResult = findViewById(R.id.imgResult)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)

        // 事件绑定
        btnSelectFolder.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        btnStart.setOnClickListener { startLottery() }
        btnStop.setOnClickListener { stopLottery() }
    }

    // 从用户选择的系统目录中解析照片
    private fun parsePhotosFromDirectory(treeUri: Uri) {
        studentList.clear()
        val rootFolder = DocumentFile.fromTreeUri(this, treeUri) ?: return
        
        for (file in rootFolder.listFiles()) {
            if (file.isFile && file.name?.endsWith(".png", ignoreCase = true) == true) {
                val fileName = file.name?.removeSuffix(".png") ?: continue
                val parts = fileName.split("_")

                // 智能解析 3段、2段 和 1段 命名方式
                val (id, name, title) = when {
                    parts.size >= 3 -> Triple(parts[0], parts[1], parts.subList(2, parts.size).joinToString("_"))
                    parts.size == 2 -> Triple(parts[0], parts[1], "")
                    else -> Triple(fileName, "同学", "")
                }

                studentList.add(Student(id, name, title, file.uri))
            }
        }
        tvResultId.text = "已加载 ${studentList.size} 人"
    }

    // 开始抽签
    private fun startLottery() {
        val available = studentList.filter { it.isActive && !it.isInCooldown }
        if (available.isEmpty()) return

        isRolling = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        rollRunnable.run()
    }

    // 停止抽签
    private fun stopLottery() {
        isRolling = false
        handler.removeCallbacks(rollRunnable)

        // 播放系统内置提示音（成功音效）
        toneGen.startTone(ToneGenerator.TONE_PROP_ACK)

        val available = studentList.filter { it.isActive && !it.isInCooldown }
        if (available.isNotEmpty()) {
            val winner = available[Random().nextInt(available.size)]
            winner.isInCooldown = true // 标记为已抽取/冷却
            displayStudent(winner)
        }

        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    // 滚动定时任务
    private val rollRunnable = object : Runnable {
        override fun run() {
            if (isRolling) {
                val available = studentList.filter { it.isActive && !it.isInCooldown }
                if (available.isNotEmpty()) {
                    val randomStudent = available[Random().nextInt(available.size)]
                    displayStudent(randomStudent)
                }
                handler.postDelayed(this, 80) // 80毫秒刷新一次照片
            }
        }
    }

    // 渲染学生画面
    private fun displayStudent(student: Student) {
        tvResultId.text = "学号: ${student.id}"
        tvResultName.text = "姓名: ${student.name}"
        tvResultTitle.text = if (student.title.isNotEmpty()) "职务: ${student.title}" else ""
        imgResult.setImageURI(student.uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGen.release()
    }
}

