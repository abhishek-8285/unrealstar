package com.unrealstar.live

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.unrealstar.live.api.SessionStore
import com.unrealstar.live.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore persisted session (or debug guest token) before any feed call.
        SessionStore.init(this)
        // Opt in explicitly so window-inset behaviour is identical on every API
        // level. Left implicit, Android 15 forces edge-to-edge (targetSdk 35)
        // while older releases do not, and the same inset code would then be
        // correct on one and double-counted on the other.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        // The "+" opens the existing camera screen. CameraLiveActivity is a
        // separate Activity on purpose, so its CameraX lifecycle code is
        // untouched and the bar is naturally hidden while broadcasting.
        binding.btnCreate.setOnClickListener {
            startActivity(Intent(this, CameraLiveActivity::class.java))
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> show(HomeFragment())
                R.id.nav_live -> show(LiveFragment())
                R.id.nav_inbox -> show(InboxFragment())
                R.id.nav_me -> show(MeFragment())
                // The centre slot is an invisible spacer, not a destination.
                // Return false so it never becomes the selected tab.
                R.id.nav_create -> return@setOnItemSelectedListener false
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        // On rotation the FragmentManager restores the fragment and
        // BottomNavigationView restores its own checked item, so only select
        // Home on a genuinely fresh launch.
        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_home
        }
    }

    private fun applyWindowInsets() {
        // Only the bottom bar needs handling here. The top inset is owned by
        // each fragment's own header, so the feed video can still run
        // full-bleed behind the status bar the way the reference does.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomNav.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}