package com.company.callmonitor

import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Binds the Shift switch on a home screen to [ShiftManager].
 *
 * Shared by the driver and employee screens because the switch has to behave
 * identically on both — it is the same consent boundary, and a toggle that
 * confirmed on one screen but not the other would be a trap.
 *
 * @param hintWhenOn what the user is agreeing to record, named plainly.
 * @param preTurnOn veto hook, e.g. missing permissions. Returning false reverts
 *        the switch without starting a shift.
 */
class ShiftToggleController(
    private val activity: AppCompatActivity,
    private val switch: SwitchCompat,
    private val statusView: TextView,
    private val hintView: TextView,
    private val hintWhenOn: String,
    private val hintWhenOff: String,
    private val preTurnOn: (() -> Boolean)? = null,
    private val onChanged: (() -> Unit)? = null
) {

    private var suppressListener = false

    fun attach() {
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListener) return@setOnCheckedChangeListener
            if (isChecked) requestTurnOn() else confirmTurnOff()
        }
        refresh()
    }

    /** Repaints from stored state. Safe to call from onResume and on a timer. */
    fun refresh() {
        val on = ShiftManager.isOn(activity)
        suppressListener = true
        switch.isChecked = on
        suppressListener = false

        switch.text = if (on) "ON" else "OFF"
        statusView.text = ShiftManager.statusLine(activity)
        statusView.setTextColor(if (on) COLOR_ON else COLOR_OFF)
        hintView.text = if (on) hintWhenOn else hintWhenOff
    }

    private fun requestTurnOn() {
        if (preTurnOn?.invoke() == false) {
            revert(false)
            return
        }
        ShiftManager.turnOn(activity, "user_toggle")
        refresh()
        onChanged?.invoke()
    }

    /**
     * Ending a shift stops every collector and is what the audit trail will be
     * read against, so it is worth one tap of confirmation — an accidental brush
     * of the switch should not silently end someone's recorded working day.
     */
    private fun confirmTurnOff() {
        AlertDialog.Builder(activity)
            .setTitle("End your shift?")
            .setMessage(
                "Recording stops now. Your shift start and end times are saved for " +
                    "your records."
            )
            .setPositiveButton("End shift") { _, _ ->
                ShiftManager.turnOff(activity, "user_toggle")
                refresh()
                onChanged?.invoke()
            }
            .setNegativeButton("Keep working") { _, _ -> revert(true) }
            .setOnCancelListener { revert(true) }
            .show()
    }

    private fun revert(toChecked: Boolean) {
        suppressListener = true
        switch.isChecked = toChecked
        suppressListener = false
        refresh()
    }

    private companion object {
        const val COLOR_ON = 0xFF1B5E20.toInt()
        const val COLOR_OFF = 0xFFB71C1C.toInt()
    }
}
