package com.example.hybridemulator

import android.content.Intent
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Native-style configuration dashboard for the offline ISO/IMG virtual
 * machine, conceptually modeled after Limbo/QEMU front-ends: pick RAM size,
 * CPU architecture, and a local boot disk image.
 *
 * Two real, non-placeholder offline engines are offered:
 *  - "Boot Virtual Machine" hands the configuration to [OfflineVmActivity],
 *    which runs it on the bundled, offline v86 engine (x86 32-bit only, but
 *    instant — no separate install, boots entirely inside this app).
 *  - "Boot with Limbo (Real QEMU, x86_64)" launches the real QEMU-based
 *    Limbo engine for genuine 64-bit x86_64 CPU emulation. Limbo is built
 *    from its own upstream source by the "build-limbo-engine" job in
 *    .github/workflows/build.yml and ships as its own separate, GPL-
 *    licensed APK (see README notes) — this app hands off to it rather
 *    than re-implementing QEMU itself.
 */
class LimboStyleActivity : AppCompatActivity() {

    private val ramOptions = listOf("256 MB", "512 MB", "1 GB", "2 GB")
    private val cpuOptions = listOf("x86", "x86_64", "arm")

    private var selectedImageUri: Uri? = null
    private var selectedImageName: String? = null

    private lateinit var selectedFileText: TextView
    private lateinit var bootButton: Button

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            handlePickedUri(uri)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
    }

    private fun buildRootView(): ScrollView {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        scroll.addView(container)

        container.addView(TextView(this).apply {
            text = "Local ISO / IMG Virtual Machine"
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        container.addView(TextView(this).apply {
            text = "Configure the offline emulator before booting."
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(20))
        })

        // RAM selector
        container.addView(fieldLabel("Memory (RAM)"))
        val ramSpinner = buildSpinner(ramOptions, defaultIndex = 2) // default: 1 GB
        container.addView(ramSpinner)

        // CPU architecture selector
        container.addView(fieldLabel("CPU Architecture").apply { setPadding(0, dp(18), 0, dp(4)) })
        val cpuSpinner = buildSpinner(cpuOptions, defaultIndex = 0) // default: x86
        container.addView(cpuSpinner)

        // Boot image picker
        container.addView(fieldLabel("Boot Disk Image (.iso / .img)").apply { setPadding(0, dp(18), 0, dp(4)) })
        selectedFileText = TextView(this).apply {
            text = "No file selected"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(2), dp(4), dp(2), dp(10))
        }
        container.addView(selectedFileText)

        val pickButton = Button(this).apply {
            text = "Select ISO / IMG File"
            setOnClickListener {
                // Any file type is requested here because .iso / .img do not
                // have a reliably registered MIME type across Android
                // storage providers; the extension is validated afterward.
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
        }
        container.addView(pickButton)

        // Boot button
        bootButton = Button(this).apply {
            text = "Boot Virtual Machine"
            isEnabled = false
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                val ram = ramSpinner.selectedItem.toString()
                val cpu = cpuSpinner.selectedItem.toString()
                startBoot(ram, cpu)
            }
        }
        container.addView(bootButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })

        container.addView(TextView(this).apply {
            text = "Select a boot image to enable the Boot button."
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        // Real QEMU (Limbo) hand-off — genuine x86_64 CPU emulation
        container.addView(TextView(this).apply {
            text = "Real QEMU Engine (x86_64)"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, dp(28), 0, dp(4))
        })
        container.addView(TextView(this).apply {
            text = "Launches the real QEMU-based Limbo engine for true 64-bit x86_64 " +
                "CPU emulation. Limbo opens as its own screen — pick your ISO/IMG " +
                "there using its CDROM/HDA option."
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, dp(10))
        })
        container.addView(Button(this).apply {
            text = "Boot with Limbo (Real QEMU, x86_64)"
            setOnClickListener { launchLimboEngine() }
        })

        return scroll
    }

    /**
     * Hands off to the real QEMU-based Limbo app for genuine x86_64
     * emulation. Limbo is a separate, independently-built app (see
     * .github/workflows/build.yml's build-limbo-engine job) rather than
     * code merged into this APK's process, so this app does not pass it
     * the picked file directly — the user selects it inside Limbo's own
     * CDROM/HDA screen after it opens.
     */
    private fun launchLimboEngine() {
        val limboPackage = "com.limbo.emu.main"
        val launchIntent = packageManager.getLaunchIntentForPackage(limboPackage)
        if (launchIntent != null) {
            Toast.makeText(
                this,
                "Opening Limbo — pick your ISO/IMG inside its CDROM/HDA screen",
                Toast.LENGTH_LONG
            ).show()
            startActivity(launchIntent)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Limbo Not Installed")
                .setMessage(
                    "The real QEMU x86_64 engine ships as a separate app (package " +
                        "$limboPackage), built by this project's \"build-limbo-engine\" " +
                        "GitHub Actions job from the upstream limboemu/limbo source. " +
                        "Install the APK produced by that workflow run (artifact: " +
                        "limbo-x86_64-engine-apk) on this device, then try again."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
    }

    private fun buildSpinner(options: List<String>, defaultIndex: Int): Spinner {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(defaultIndex)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {}
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun handlePickedUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers do not support persistable permissions; the
            // read grant is still valid for this session.
        }

        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "selected_file"
        val lower = displayName.lowercase()
        if (!lower.endsWith(".iso") && !lower.endsWith(".img")) {
            Toast.makeText(this, "Warning: file does not end in .iso or .img", Toast.LENGTH_LONG).show()
        }

        selectedImageUri = uri
        selectedImageName = displayName
        selectedFileText.text = displayName
        bootButton.isEnabled = true
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * Validates the configuration and boots it with the real, offline v86
     * x86 engine (see OfflineVmActivity). v86 only emulates an x86-class
     * CPU — it cannot execute x86_64 long mode or ARM instructions — so a
     * non-x86 selection is flagged honestly instead of silently booting
     * something it did not actually run.
     */
    private fun startBoot(ramLabel: String, cpu: String) {
        val uri = selectedImageUri
        val name = selectedImageName
        if (uri == null || name == null) {
            Toast.makeText(this, "Select a boot image first", Toast.LENGTH_SHORT).show()
            return
        }

        val ramMb = ramLabelToMb(ramLabel)
        val kind = if (name.lowercase().endsWith(".img")) "hda" else "cdrom"

        if (cpu != "x86") {
            AlertDialog.Builder(this)
                .setTitle("CPU Architecture Notice")
                .setMessage(
                    "The bundled offline engine (v86) emulates a real x86 CPU only — " +
                        "it cannot execute $cpu instructions. Continue booting this image " +
                        "on the x86 core instead?"
                )
                .setPositiveButton("Continue with x86") { _, _ -> launchOfflineVm(uri, ramMb, kind) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            launchOfflineVm(uri, ramMb, kind)
        }
    }

    private fun ramLabelToMb(label: String): Int = when (label) {
        "256 MB" -> 256
        "512 MB" -> 512
        "1 GB" -> 1024
        "2 GB" -> 2048
        else -> 1024
    }

    private fun launchOfflineVm(uri: Uri, ramMb: Int, kind: String) {
        val intent = Intent(this, OfflineVmActivity::class.java).apply {
            putExtra(OfflineVmActivity.EXTRA_IMAGE_URI, uri.toString())
            putExtra(OfflineVmActivity.EXTRA_RAM_MB, ramMb)
            putExtra(OfflineVmActivity.EXTRA_KIND, kind)
        }
        startActivity(intent)
    }
}
