package com.company.callmonitor

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry-point activity. Routes the user to the correct screen without showing any UI.
 * - Not enrolled → EnrollmentActivity
 * - Role "driver" → DriverHomeActivity
 * - Other roles → EmployeeHomeActivity
 *
 * Also handles OEM onboarding the same way MainActivity does.
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OemPowerSettingsHelper.isAggressivePowerOem() && !OemOnboardingActivity.isCompleted(this)) {
            startActivity(Intent(this, OemOnboardingActivity::class.java))
        }

        val next: Intent = if (!EnrollmentPrefs.isEnrolled(this)) {
            Intent(this, EnrollmentActivity::class.java)
        } else {
            when (EnrollmentPrefs.getRole(this).lowercase()) {
                "driver" -> Intent(this, DriverHomeActivity::class.java)
                else -> Intent(this, EmployeeHomeActivity::class.java)
            }
        }

        next.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(next)
        finish()
    }
}
