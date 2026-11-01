package com.example.hybridemulator

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout

/**
 * Main dashboard for the Hybrid Emulator app.
 *
 * The screen is split into two sections selectable via a TabLayout:
 *  - "Cloud v86 Emulators": launches [V86Activity] with a preset or custom
 *    copy.sh/v86 profile URL, rendered inside a WebView.
 *  - "Local ISO VM": launches [LimboStyleActivity], a native configuration
 *    screen for RAM/CPU selection and local ISO/IMG file picking.
 *
 * Every view in this screen is built programmatically (no XML layouts) for
 * maximum compatibility with headless / CI-only builds.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var contentContainer: FrameLayout

    // Curated list of known-working v86 demo profiles hosted on copy.sh.
    private val v86Profiles = listOf(
        "Windows 98" to "https://copy.sh/v86/?profile=windows98",
        "Windows 95" to "https://copy.sh/v86/?profile=windows95",
        "Windows 2000" to "https://copy.sh/v86/?profile=windows2000",
        "MS-DOS" to "https://copy.sh/v86/?profile=msdos",
        "FreeDOS" to "https://copy.sh/v86/?profile=freedos",
        "Damn Small Linux" to "https://copy.sh/v86/?profile=dsl",
        "Arch Linux" to "https://copy.sh/v86/?profile=archlinux",
        "ReactOS" to "https://copy.sh/v86/?profile=reactos",
        "KolibriOS" to "https://copy.sh/v86/?profile=kolibrios"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootView())
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun buildRootView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(ContextCompat.getColor(context, R.color.background))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "v86 web emulation + native local ISO/IMG virtual machines"
            setTextColor(Color.parseColor("#D7DAFF"))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Tabs
        val tabLayout = TabLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.primary_dark))
            setSelectedTabIndicatorColor(ContextCompat.getColor(context, R.color.accent))
            setTabTextColors(Color.parseColor("#AEB4E6"), Color.WHITE)
            addTab(newTab().setText(getString(R.string.tab_cloud)))
            addTab(newTab().setText(getString(R.string.tab_local)))
        }
        root.addView(tabLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Content container that swaps between the two sections
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(contentContainer)

        val cloudView = buildCloudSection()
        val localView = buildLocalSection()
        contentContainer.addView(cloudView)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                contentContainer.removeAllViews()
                contentContainer.addView(if (tab.position == 0) cloudView else localView)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        return root
    }

    // ---------------------------------------------------------------
    // Section 1: Cloud v86 emulators
    // ---------------------------------------------------------------

    private fun buildCloudSection(): View {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(container)

        container.addView(sectionTitle("Preset Operating Systems"))
        container.addView(sectionSubtitle("Runs entirely in-browser via the v86 WebAssembly emulator from copy.sh. Requires an internet connection."))

        for ((name, url) in v86Profiles) {
            container.addView(buildProfileCard(name, url))
        }

        container.addView(sectionTitle("Custom v86 URL").apply {
            setPadding(0, dp(20), 0, dp(4))
        })
        container.addView(sectionSubtitle("Load any copy.sh/v86 profile or a custom v86 instance URL."))

        val urlInput = EditText(this).apply {
            hint = "https://copy.sh/v86/?profile=..."
            setSingleLine(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        }
        container.addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val loadButton = Button(this).apply {
            text = "Load Custom URL"
            setOnClickListener {
                val typed = urlInput.text.toString().trim()
                val target = typed.ifEmpty { "https://copy.sh/v86/" }
                launchV86("Custom", target)
            }
        }
        container.addView(loadButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        return scroll
    }

    private fun buildProfileCard(name: String, url: String): View {
        val card = MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            cardElevation = dp(2).toFloat()
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { launchV86(name, url) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = name
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        })
        textColumn.addView(TextView(this).apply {
            text = url
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            maxLines = 1
        })
        row.addView(textColumn)

        row.addView(TextView(this).apply {
            text = "LAUNCH ▶"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            setTypeface(typeface, Typeface.BOLD)
        })

        card.addView(row)
        return card
    }

    private fun launchV86(name: String, url: String) {
        val intent = Intent(this, V86Activity::class.java).apply {
            putExtra(V86Activity.EXTRA_TITLE, name)
            putExtra(V86Activity.EXTRA_URL, url)
        }
        startActivity(intent)
    }

    // ---------------------------------------------------------------
    // Section 2: Local ISO VM
    // ---------------------------------------------------------------

    private fun buildLocalSection(): View {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(container)

        container.addView(sectionTitle("Local ISO / IMG Virtual Machine"))
        container.addView(sectionSubtitle("Configure a native-style virtual machine profile (RAM, CPU architecture) and select a boot disk image stored on your device. No internet connection required."))

        val card = MaterialCardView(this).apply {
            radius = dp(10).toFloat()
            cardElevation = dp(2).toFloat()
            setContentPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }
        val cardContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardContent.addView(TextView(this).apply {
            text = "Offline Emulator Setup"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        cardContent.addView(TextView(this).apply {
            text = "Choose RAM size, CPU architecture, and a local .iso / .img file, then boot the virtual machine interface."
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(16))
        })
        cardContent.addView(Button(this).apply {
            text = "Configure & Launch Local VM"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LimboStyleActivity::class.java))
            }
        })
        card.addView(cardContent)
        container.addView(card)

        return scroll
    }

    // ---------------------------------------------------------------
    // Shared small helpers
    // ---------------------------------------------------------------

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
    }

    private fun sectionSubtitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(0, dp(2), 0, dp(4))
    }
}
