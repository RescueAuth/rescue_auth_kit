package com.rescueauth.v2

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal Activity for Phase 1 platform smoke validation. UI is intentionally
 * out of scope until the legacy import compatibility suite passes.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 1: no UI — reserved for Room/SQLCipher/Keystore/Biometric/SAF
        // lifecycle validation in Phase 2+.
    }
}
