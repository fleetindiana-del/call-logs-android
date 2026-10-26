package com.company.callmonitor

import android.content.res.ColorStateList
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The driver's Shift On / Shift Off button and the panel around it.
 *
 * It states, in one place and in plain words, everything the driver needs to know
 * about the shift: which of the two shifts it is and its scheduled hours, whether
 * the shift is on, whether tracking is actually working, and how long it has run.
 * "On shift" is only ever shown with a green "tracking active" — when tracking has
 * failed the panel says so, with the reason, rather than leave the driver believing
 * they are being recorded.
 *
 * Switching on offers only the shifts that are currently open (30 minutes before
 * the scheduled start until the scheduled end). Switching off asks for one tap of
 * confirmation, because the end time is what the audit trail is read against.
 *
 * @param preTurnOn veto hook, e.g. missing location permission. Returning false
 *        leaves the shift off; starting a shift the phone cannot honour would have
 *        the driver believing their route is recorded when nothing is.
 */
class ShiftButtonController(
    private val activity: AppCompatActivity,
    private val button: Button,
    private val nameView: TextView,
    private val statusView: TextView,
    private val hintView: TextView,
    private val preTurnOn: (() -> Boolean)? = null,
    private val onChanged: (() -> Unit)? = null
) {

    fun attach() {
        button.setOnClickListener {
            if (ShiftManager.isOn(activity)) confirmTurnOff() else requestTurnOn()
        }
        refresh()
    }

    /** Repaints from stored state. Safe to call from onResume and on a timer. */
    fun refresh() {
        // The schedule closes the shift even if nothing else has run since its end.
        ShiftManager.enforceSchedule(activity)

        if (ShiftManager.isOn(activity)) paintOn() else paintOff()
    }

    private fun paintOn() {
        val cfg = ShiftEventReporter.config(activity)
        val def = cfg.shifts.firstOrNull { it.type == ShiftManager.shiftType(activity) }
        val label = def?.label ?: "Shift"
        nameView.text = if (def != null) "$label · ${ShiftSchedule.hoursLabel(def)}" else label

        val now = System.currentTimeMillis()
        val started = ShiftManager.startedAt(activity)
        val cause = ShiftEventReporter.openTrackingCause(activity)
        val offlineSince = ShiftEventReporter.connectivityLostSince(activity)

        when {
            cause != null -> {
                val since = ShiftEventReporter.openTrackingSince(activity)
                val for_ = if (since > 0L && now - since >= 60_000L) " · ${ShiftManager.formatDuration(now - since)}" else ""
                statusView.text = "TRACKING INTERRUPTED — ${ShiftEventReporter.causeLabel(cause)}$for_"
                statusView.setTextColor(COLOR_WARN)
            }
            offlineSince > 0L -> {
                statusView.text = "ON SHIFT — no internet, still recording"
                statusView.setTextColor(COLOR_AMBER)
            }
            else -> {
                statusView.text = "ON SHIFT — tracking active"
                statusView.setTextColor(COLOR_ON)
            }
        }

        val scheduledMs = (ShiftManager.scheduledEnd(activity) - ShiftManager.scheduledStart(activity)).coerceAtLeast(0L)
        val running = "Started ${ShiftManager.formatTime(started)} · ${ShiftManager.formatDuration(now - started)}"
        statusView.append(
            "\n" + running + if (scheduledMs > 0L) " of ${ShiftManager.formatDuration(scheduledMs)}" else ""
        )

        hintView.text = if (cause != null) {
            "Fix this so tracking resumes. If it is not restored, your shift ends automatically after an hour."
        } else {
            "Your location is recorded only while your shift is on."
        }

        button.text = "Shift Off"
        button.isEnabled = true
        button.backgroundTintList = ColorStateList.valueOf(COLOR_OFF_BUTTON)
    }

    private fun paintOff() {
        val open = ShiftManager.availableShifts(activity)
        val cfg = ShiftEventReporter.config(activity)

        nameView.text = if (open.isNotEmpty()) {
            open.joinToString("\n") { w ->
                val def = cfg.shifts.firstOrNull { it.type == w.type }
                if (def != null) "${w.label} · ${ShiftSchedule.hoursLabel(def)}" else w.label
            }
        } else {
            "No shift is open right now"
        }

        val ended = ShiftManager.endedAt(activity)
        statusView.text = if (ended > 0L) "OFF SHIFT — last ended ${ShiftManager.formatTime(ended)}" else "OFF SHIFT"
        statusView.setTextColor(COLOR_OFF_TEXT)

        val note = ShiftManager.lastEndNote(activity)
        val next = if (open.isEmpty()) ShiftManager.nextShiftOpening(activity) else null
        hintView.text = when {
            note.isNotEmpty() && open.isEmpty() -> "$note\n${nextOpeningText(next, cfg)}"
            note.isNotEmpty() -> note
            open.isEmpty() -> nextOpeningText(next, cfg)
            else -> "Tap Shift On to start. Location tracking begins automatically."
        }

        button.text = "Shift On"
        button.isEnabled = open.isNotEmpty()
        button.backgroundTintList = ColorStateList.valueOf(if (open.isNotEmpty()) COLOR_ON_BUTTON else COLOR_DISABLED)
    }

    private fun nextOpeningText(next: ShiftSchedule.Window?, cfg: ShiftSchedule.Config): String {
        if (next == null) return "Shifts open shortly before their start time."
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(cfg.timezone) }
        val opensAt = fmt.format(Date(next.startMs - cfg.rules.earlyStartMs))
        return "Next: ${next.label} opens at $opensAt."
    }

    private fun requestTurnOn() {
        val open = ShiftManager.availableShifts(activity)
        if (open.isEmpty()) {
            Toast.makeText(activity, "No shift is open right now", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        if (preTurnOn?.invoke() == false) return

        if (open.size == 1) {
            start(open[0].type)
            return
        }

        // Around a handover both shifts are open; the driver says which one this is.
        val cfg = ShiftEventReporter.config(activity)
        val names = open.map { w ->
            val def = cfg.shifts.firstOrNull { it.type == w.type }
            if (def != null) "${w.label} (${ShiftSchedule.hoursLabel(def)})" else w.label
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Which shift are you starting?")
            .setItems(names) { _, which -> start(open[which].type) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun start(type: String) {
        ShiftManager.turnOn(activity, "user_toggle", type)
        refresh()
        onChanged?.invoke()
    }

    /**
     * Ending a shift stops every collector and is what the audit trail will be
     * read against, so it is worth one tap of confirmation — an accidental brush
     * of the button should not silently end someone's recorded working day.
     */
    private fun confirmTurnOff() {
        AlertDialog.Builder(activity)
            .setTitle("End your shift?")
            .setMessage(
                "Location tracking stops now. Your shift start and end times are saved for your records."
            )
            .setPositiveButton("End shift") { _, _ ->
                ShiftManager.turnOff(activity, "user_toggle")
                refresh()
                onChanged?.invoke()
            }
            .setNegativeButton("Keep working", null)
            .show()
    }

    private companion object {
        const val COLOR_ON = 0xFF1B5E20.toInt()
        const val COLOR_AMBER = 0xFFE65100.toInt()
        const val COLOR_WARN = 0xFFB71C1C.toInt()
        const val COLOR_OFF_TEXT = 0xFF616161.toInt()
        const val COLOR_ON_BUTTON = 0xFF2E7D32.toInt()
        const val COLOR_OFF_BUTTON = 0xFFC62828.toInt()
        const val COLOR_DISABLED = 0xFF9E9E9E.toInt()
    }
}
