package com.sidedeck.notes

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import java.util.Calendar

/**
 * Native custom Date + Time picker flow for note reminders.
 */
object DateTimePickerHelper {
    fun pickDateTime(
        context: Context,
        initialTimeMillis: Long? = null,
        onPicked: (Long) -> Unit
    ) {
        val cal = Calendar.getInstance().apply {
            if (initialTimeMillis != null && initialTimeMillis > System.currentTimeMillis()) {
                timeInMillis = initialTimeMillis
            } else {
                add(Calendar.HOUR_OF_DAY, 1)
            }
        }

        val startYear = cal.get(Calendar.YEAR)
        val startMonth = cal.get(Calendar.MONTH)
        val startDay = cal.get(Calendar.DAY_OF_MONTH)

        val datePicker = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val startHour = cal.get(Calendar.HOUR_OF_DAY)
                val startMinute = cal.get(Calendar.MINUTE)

                val timePicker = TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        val selected = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            set(Calendar.HOUR_OF_DAY, hourOfDay)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                        }
                        val chosenMillis = selected.timeInMillis
                        if (chosenMillis > System.currentTimeMillis()) {
                            onPicked(chosenMillis)
                        } else {
                            Toast.makeText(context, "Please choose a future time", Toast.LENGTH_SHORT).show()
                        }
                    },
                    startHour,
                    startMinute,
                    false
                )
                timePicker.setTitle("Pick reminder time")
                timePicker.show()
            },
            startYear,
            startMonth,
            startDay
        )

        datePicker.datePicker.minDate = System.currentTimeMillis() - 1000L
        datePicker.setTitle("Pick reminder date")
        datePicker.show()
    }
}
