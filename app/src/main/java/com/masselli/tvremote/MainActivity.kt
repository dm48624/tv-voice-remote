package com.masselli.tvremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var prefs: Prefs
    private lateinit var runner: Runner
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var micButton: Button
    private lateinit var heardView: TextView
    private lateinit var replyView: TextView
    private lateinit var modeButton: Button
    private lateinit var typed: EditText

    private val navy = Color.parseColor("#1E3A5F")
    private val gold = Color.parseColor("#F5B841")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        runner = Runner(prefs)
        tts = TextToSpeech(this, this)
        setContentView(buildUi())

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        } else {
            replyView.text = "Voice recognition isn't available on this tablet. Make sure the Google app is installed and up to date. You can still type commands below."
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (prefs.samsungIp.isEmpty() && prefs.rokuIp.isEmpty()) {
            replyView.text = "Welcome! Tap Settings (top right) to find your TV and Roku first."
        }
    }

    override fun onResume() {
        super.onResume()
        updateMode()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    // ---------- voice ----------

    private fun startListening() {
        val r = recognizer ?: return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (listening) { r.stopListening(); return }
        tts?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        listening = true
        micButton.text = "Listening…"
        r.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { heardView.text = "Listening…" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { micButton.text = "Working…" }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) heardView.text = "“$it”"
            }
        }

        override fun onError(error: Int) {
            listening = false
            micButton.text = MIC_LABEL
            heardView.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap and try again."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition needs the internet. Check the Wi-Fi."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need permission to use the microphone."
                else -> "Voice recognition hiccup ($error). Tap and try again."
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            micButton.text = MIC_LABEL
            val guesses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (guesses.isEmpty()) { heardView.text = "I didn't catch that."; return }
            heardView.text = "“${guesses[0]}”"
            replyView.text = "…"
            worker.execute {
                val (heard, reply) = runner.handleSpeech(guesses)
                ui.post {
                    heardView.text = "“$heard”"
                    showReply(reply)
                }
            }
        }
    }

    private fun runTyped() {
        val text = typed.text.toString().trim()
        if (text.isEmpty()) return
        typed.setText("")
        heardView.text = "“$text”"
        replyView.text = "…"
        worker.execute {
            val reply = runner.handleText(text)
            ui.post { showReply(reply) }
        }
    }

    private fun runCommand(c: Command) {
        replyView.text = "…"
        worker.execute {
            val reply = runner.run(c)
            ui.post { showReply(reply, speak = false) }
        }
    }

    private fun showReply(reply: String, speak: Boolean = true) {
        replyView.text = reply
        updateMode()
        if (speak && prefs.speak && ttsReady) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun updateMode() {
        if (::modeButton.isInitialized) {
            modeButton.text = if (prefs.mode == "roku") "Arrows control: ROKU  (tap to switch)" else "Arrows control: TV  (tap to switch)"
        }
    }

    // ---------- layout ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun button(label: String, textSp: Float = 20f, bg: Int = navy, fg: Int = Color.WHITE, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            setTextColor(fg)
            background = rounded(bg)
            minHeight = dp(64)
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View, heightDp: Int = 72): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
    }

    private fun spacer() = View(this)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#F4F6F9"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "TV Voice Remote"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(navy)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("⚙ Settings", 18f, Color.parseColor("#DDE3EA"), navy) {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56)))
        }
        root.addView(header)

        micButton = button(MIC_LABEL, 30f, gold, navy) { startListening() }
        root.addView(micButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)).apply { topMargin = dp(16) })

        heardView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(14), dp(4), dp(4))
            text = "Try: “Go to 9&10”, “Find NCIS”, “Volume up”, “Channel down”"
        }
        root.addView(heardView)

        replyView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(navy)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), dp(14))
        }
        root.addView(replyView)

        typed = EditText(this).apply {
            hint = "…or type a command"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> runTyped(); true }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(typed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Go", 20f) { runTyped() }, LinearLayout.LayoutParams(dp(90), dp(60)))
        })

        root.addView(row(
            button("TV On", bg = Color.parseColor("#2E7D32")) { runCommand(Command.Power(true)) },
            button("TV Off", bg = Color.parseColor("#B23B3B")) { runCommand(Command.Power(false)) },
            button("Mute") { runCommand(Command.Mute) }
        ).apply { setPadding(0, dp(12), 0, 0) })
        root.addView(row(
            button("Vol −", 26f) { runCommand(Command.Volume(false, 2)) },
            button("Vol +", 26f) { runCommand(Command.Volume(true, 2)) },
            button("Ch −", 26f) { runCommand(Command.ChannelStep(false)) },
            button("Ch +", 26f) { runCommand(Command.ChannelStep(true)) },
            heightDp = 90
        ))

        modeButton = button("", 18f, Color.parseColor("#DDE3EA"), navy) {
            prefs.mode = if (prefs.mode == "roku") "tv" else "roku"
            updateMode()
        }
        root.addView(modeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })

        root.addView(row(spacer(), button("▲", 28f) { runCommand(Command.Key(Nav.UP)) }, spacer()))
        root.addView(row(
            button("◀", 28f) { runCommand(Command.Key(Nav.LEFT)) },
            button("OK", 24f, gold, navy) { runCommand(Command.Key(Nav.OK)) },
            button("▶", 28f) { runCommand(Command.Key(Nav.RIGHT)) }
        ))
        root.addView(row(spacer(), button("▼", 28f) { runCommand(Command.Key(Nav.DOWN)) }, spacer()))
        root.addView(row(
            button("Back") { runCommand(Command.Key(Nav.BACK)) },
            button("Home") { runCommand(Command.Key(Nav.HOME)) },
            button("Guide") { runCommand(Command.Key(Nav.GUIDE)) },
            button("Info") { runCommand(Command.Key(Nav.INFO)) }
        ))
        root.addView(row(
            button("⏪", 26f) { runCommand(Command.Key(Nav.REWIND)) },
            button("⏯", 26f) { runCommand(Command.Key(Nav.PLAY)) },
            button("⏸", 26f) { runCommand(Command.Key(Nav.PAUSE)) },
            button("⏩", 26f) { runCommand(Command.Key(Nav.FAST_FORWARD)) }
        ))
        root.addView(row(
            button("Live TV") { runCommand(Command.SwitchInput(Source.ANTENNA)) },
            button("Roku") { runCommand(Command.SwitchInput(Source.ROKU)) },
            button("Captions") { runCommand(Command.Key(Nav.CAPTIONS)) }
        ))

        updateMode()
        return ScrollView(this).apply { addView(root); isFillViewport = true }
    }

    companion object {
        const val MIC_LABEL = "🎤  Tap and speak"
    }
}
