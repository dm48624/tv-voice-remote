package com.masselli.tvremote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvIp: EditText
    private lateinit var rokuIp: EditText
    private lateinit var rokuHdmi: EditText
    private lateinit var channels: EditText
    private lateinit var speak: CheckBox
    private lateinit var autoTune: CheckBox
    private lateinit var sdUser: EditText
    private lateinit var sdPass: EditText
    private lateinit var sdZip: EditText
    private lateinit var searchKeys: EditText
    private lateinit var status: TextView

    private val navy = Color.parseColor("#1E3A5F")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun save() {
        if (tvIp.text.toString().trim() != prefs.samsungIp) prefs.samsungToken = ""
        prefs.samsungIp = tvIp.text.toString()
        prefs.rokuIp = rokuIp.text.toString()
        prefs.rokuHdmi = rokuHdmi.text.toString()
        prefs.channelsText = channels.text.toString()
        prefs.speak = speak.isChecked
        prefs.autoTune = autoTune.isChecked
        val user = sdUser.text.toString().trim()
        if (user != prefs.sdUser) { prefs.sdToken = ""; prefs.sdLineup = "" }
        prefs.sdUser = user
        val pass = sdPass.text.toString()
        if (pass.isNotEmpty()) {
            prefs.sdPasswordHash = SchedulesDirect.sha1(pass)
            prefs.sdToken = ""
            sdPass.setText("")
            sdPass.hint = "saved (type again only to change it)"
        }
        if (sdZip.text.toString().trim() != prefs.sdZip) prefs.sdLineup = ""
        prefs.sdZip = sdZip.text.toString()
        prefs.samsungSearchKeys = searchKeys.text.toString().ifBlank { Prefs.DEFAULT_SEARCH_KEYS }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun label(text: String, sp: Float = 18f, bold: Boolean = true) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(if (bold) navy else Color.DKGRAY)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun field(value: String, hint: String, numeric: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setSingleLine()
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setOnClickListener { onClick() }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
        }
        root.addView(TextView(this).apply {
            text = "Settings"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(navy)
        })

        status = label("", 18f, bold = false)

        root.addView(button("🔍  Find my TV and Roku on the Wi-Fi") { findDevices() })
        root.addView(status)

        root.addView(label("Samsung TV address"))
        tvIp = field(prefs.samsungIp, "e.g. 192.168.1.50"); root.addView(tvIp)
        root.addView(button("Pair with the TV (watch the TV for an Allow box)") { pairTv() })

        root.addView(label("Roku address"))
        rokuIp = field(prefs.rokuIp, "e.g. 192.168.1.60"); root.addView(rokuIp)
        root.addView(button("Test the Roku") { testRoku() })

        root.addView(label("Which HDMI port is the Roku plugged into? (1, 2, 3 or 4)"))
        root.addView(label("Leave blank to let the Roku switch the TV itself (needs Anynet+ turned on in the TV's settings).", 15f, bold = false))
        rokuHdmi = field(prefs.rokuHdmi, "blank, or 1-4", numeric = true); root.addView(rokuHdmi)

        root.addView(label("Buttons that open Samsung's search screen"))
        root.addView(label("The app presses these to get to Search, then types what you asked for. Use remote button names separated by commas; *3 repeats a button 3 times; wait pauses a second. Example: HOME, wait, LEFT*12, RIGHT*1, ENTER", 15f, bold = false))
        searchKeys = field(prefs.samsungSearchKeys, Prefs.DEFAULT_SEARCH_KEYS); root.addView(searchKeys)
        root.addView(button("Test Samsung search (looks for NCIS)") { testSamsungSearch() })
        root.addView(button("Reset search buttons to the default") { searchKeys.setText(Prefs.DEFAULT_SEARCH_KEYS) })

        speak = CheckBox(this).apply {
            text = "Speak replies out loud"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isChecked = prefs.speak
        }
        root.addView(speak)
        autoTune = CheckBox(this).apply {
            text = "When a show I ask for is on now, switch to it without asking"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isChecked = prefs.autoTune
        }
        root.addView(autoTune)
        root.addView(button("Check the TV listings") { checkListings() })

        root.addView(label("Antenna listings (Schedules Direct)"))
        root.addView(label("Sign up at schedulesdirect.org, then enter the same username and password here and tap Connect. Your password is stored only in scrambled form.", 15f, bold = false))
        sdUser = field(prefs.sdUser, "Schedules Direct username"); root.addView(sdUser)
        sdPass = field("", if (prefs.sdPasswordHash.isEmpty()) "Schedules Direct password" else "saved (type again only to change it)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(sdPass)
        root.addView(CheckBox(this).apply {
            text = "Show the password while I type it"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnCheckedChangeListener { _, on ->
                sdPass.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                sdPass.setSelection(sdPass.text.length)
            }
        })
        root.addView(label("ZIP code for your antenna channels", 15f, bold = false))
        sdZip = field(prefs.sdZip, "ZIP code", numeric = true); root.addView(sdZip)
        root.addView(button("Connect to Schedules Direct and download listings") { connectSd() })
        root.addView(button("Add my antenna channels to the Channels list below") { addAntennaChannels() })

        root.addView(label("Channels"))
        root.addView(label("One per line: the number, an equals sign, then the names you might say. For example:\n9.1 = 9&10, nine and ten, 9&10 News", 15f, bold = false))
        channels = EditText(this).apply {
            setText(prefs.channelsText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 8
            setHorizontallyScrolling(false)
        }
        root.addView(channels)
        root.addView(button("Reset channel list to the example") { channels.setText(Prefs.DEFAULT_CHANNELS) })

        root.addView(label("TV settings this app needs", 18f))
        root.addView(label(
            "• To turn the TV ON by voice: TV Settings → General → Network → Expert Settings → Power On with Mobile → On.\n" +
                "• If the Roku doesn't respond: Roku Settings → System → Advanced system settings → Control by mobile apps → Network access → Permissive.\n" +
                "• Give the TV and Roku fixed addresses in your router if they keep changing.",
            15f, bold = false
        ))

        root.addView(label("Version ${packageManager.getPackageInfo(packageName, 0).versionName}", 14f, bold = false))

        return ScrollView(this).apply { addView(root) }
    }

    private fun findDevices() {
        status.text = "Looking on the Wi-Fi… (about 5 seconds)"
        worker.execute {
            val found = try { Discovery.scan(this) } catch (e: Exception) { emptyList() }
            ui.post {
                if (found.isEmpty()) {
                    status.text = "Didn't find anything. Make sure the TV is on and the tablet is on the same Wi-Fi, then try again. You can also type the addresses below."
                    return@post
                }
                val tvs = found.filter { it.kind == "samsung" }
                val rokus = found.filter { it.kind == "roku" }
                if (tvs.size == 1) tvIp.setText(tvs[0].ip)
                if (rokus.size == 1) rokuIp.setText(rokus[0].ip)
                status.text = "Found:\n" + found.joinToString("\n") { it.label }
                if (tvs.size > 1 || rokus.size > 1) {
                    val items = found.map { it.label }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Tap the ones that are yours")
                        .setItems(items) { _, which ->
                            val f = found[which]
                            if (f.kind == "samsung") tvIp.setText(f.ip) else rokuIp.setText(f.ip)
                        }
                        .setPositiveButton("Done", null)
                        .show()
                }
                save()
            }
        }
    }

    private fun pairTv() {
        save()
        status.text = "Connecting… If a box appears on the TV, choose Allow with the TV remote."
        worker.execute {
            val tv = SamsungTv(prefs)
            val msg = try {
                tv.ensureConnected(40)
                tv.status()
                tv.reset()
                "Paired with the TV. ✔"
            } catch (e: TvException) {
                e.message ?: "Couldn't pair."
            }
            ui.post { status.text = msg }
        }
    }

    private fun connectSd() {
        save()
        status.text = "Connecting to Schedules Direct and downloading two days of listings… (this can take a minute)"
        worker.execute {
            val msg = Runner(prefs, filesDir).connectSchedulesDirect()
            ui.post { status.text = msg }
        }
    }

    private fun addAntennaChannels() {
        save()
        val lines = SchedulesDirect(prefs, filesDir).suggestedChannelLines(CommandParser.parseChannels(prefs.channelsText))
        if (lines.isEmpty()) {
            status.text = "Nothing to add. Connect to Schedules Direct first, or all your antenna channels are already in the list."
            return
        }
        val text = channels.text.toString().trimEnd() + "\n# Antenna channels from Schedules Direct (add names you'd say after the commas)\n" + lines.joinToString("\n") + "\n"
        channels.setText(text)
        save()
        status.text = "Added ${lines.size} antenna channels to the Channels list. You can add other names you might say after the commas."
    }

    private fun checkListings() {
        status.text = "Downloading the Samsung TV Plus listings…"
        worker.execute {
            val msg = Runner(prefs, filesDir).listingsStatus()
            ui.post { status.text = msg }
        }
    }

    private fun testSamsungSearch() {
        save()
        status.text = "Watch the TV: it should go to Search and type NCIS…"
        worker.execute {
            val msg = Runner(prefs, filesDir).testSamsungSearch()
            ui.post { status.text = msg }
        }
    }

    private fun testRoku() {
        save()
        status.text = "Checking the Roku…"
        worker.execute {
            val msg = try {
                val apps = Roku(prefs).apps()
                "The Roku answered. It has ${apps.size} apps: " + apps.take(12).joinToString(", ") { it.second }
            } catch (e: TvException) {
                e.message ?: "Couldn't reach the Roku."
            }
            ui.post { status.text = msg }
        }
    }
}
