package com.nimu.omeeeye.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nimu.omeeeye.R
import com.nimu.omeeeye.model.StepType

/**
 * "Add step" menu, grouped into sections with headers.
 *
 * NOTE on the listener: StepType has no value for "Record gesture", so the
 * sheet delivers the seven real step types through [OnStepChosenListener]
 * and record-gesture taps through the separate [onRecordRequested] lambda.
 * Both are set by TaskEditorActivity before the sheet is shown, and the
 * sheet dismisses itself on any row tap.
 *
 * Sections are data, not code: later groups are added by appending another
 * [Section] to [sections] — the adapter below renders any list of sections.
 */
class AddStepBottomSheet : BottomSheetDialogFragment() {

    /** Single-method listener for the seven real step types. */
    fun interface OnStepChosenListener {
        fun onStepChosen(type: StepType)
    }

    /**
     * Forces a LIGHT dialog theme for the sheet, whatever the app's
     * DayNight/AMOLED setting is. BottomSheetDialogFragment passes
     * getTheme() to the BottomSheetDialog constructor, so this light
     * Material BottomSheetDialog theme — not the activity's dark
     * theme — decides the sheet surround; the sheet content itself
     * is already white (@color/ref_card).
     */
    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_MaterialComponents_Light_BottomSheetDialog

    var onStepChosenListener: OnStepChosenListener? = null
    var onRecordRequested: (() -> Unit)? = null
    /** Replace mode hides the Record row: recording produces several
     * new steps, so it cannot replace one step. Default true keeps
     * every existing caller (main editor, recognition sub-actions)
     * exactly as before. */
    var showRecordRow: Boolean = true

    private data class Row(
        val iconRes: Int,
        val titleRes: Int,
        val descRes: Int,
        /** Null for the Record gesture row, which is not a StepType. */
        val type: StepType?,
        val isRecord: Boolean,
        /** Shows the NEW badge beside the title (Record row only). */
        val isNew: Boolean = false
    )

    private data class Section(val titleRes: Int, val rows: List<Row>)

    // Groups in reference order: "add", "sub-task", "Logic judgment
    // function". Our extra actions follow AFTER those groups, in the
    // same row style (distinct icon + bold title + grey description):
    // "More actions" holds the remaining core steps, OCR and data
    // steps; "Advanced actions" holds the advanced actions.
    private val sections: List<Section> = listOf(
        Section(
            R.string.add_group_add,
            listOf(
                Row(
                    R.drawable.ic_step_record,
                    R.string.step_record, R.string.step_record_desc,
                    null, true, true
                ),
                Row(
                    R.drawable.ic_step_nav,
                    R.string.step_navigation, R.string.step_navigation_desc,
                    StepType.NAVIGATION, false
                ),
                Row(
                    R.drawable.ic_step_waitrandom,
                    R.string.step_wait_random, R.string.step_wait_random_desc,
                    StepType.WAIT_RANDOM, false
                ),
                Row(
                    R.drawable.ic_step_launch,
                    R.string.step_launch_app, R.string.step_launch_app_desc,
                    StepType.LAUNCH_APP, false
                )
            )
        ),
        Section(
            R.string.add_group_sub_task,
            listOf(
                Row(
                    R.drawable.ic_step_subtask,
                    R.string.step_sub_task, R.string.step_sub_task_desc,
                    StepType.SUB_TASK, false
                )
            )
        ),
        Section(
            R.string.add_group_logic,
            listOf(
                Row(
                    R.drawable.ic_step_button,
                    R.string.step_button_recognition, R.string.step_button_recognition_desc,
                    StepType.BUTTON_RECOGNITION, false
                ),
                Row(
                    R.drawable.ic_step_image,
                    R.string.step_image_recognition, R.string.step_image_recognition_desc,
                    StepType.IMAGE_RECOGNITION, false
                ),
                Row(
                    R.drawable.ic_step_color,
                    R.string.step_color_recognition, R.string.step_color_recognition_desc,
                    StepType.COLOR_RECOGNITION, false
                )
            )
        ),
        Section(
            R.string.add_group_more,
            listOf(
                Row(
                    R.drawable.ic_step_tap,
                    R.string.step_tap, R.string.step_tap_desc,
                    StepType.TAP, false
                ),
                Row(
                    R.drawable.ic_step_swipe,
                    R.string.step_swipe, R.string.step_swipe_desc,
                    StepType.SWIPE, false
                ),
                Row(
                    R.drawable.ic_step_typetext,
                    R.string.step_type_text, R.string.step_type_text_desc,
                    StepType.TYPE_TEXT, false
                ),
                Row(
                    R.drawable.ic_step_wait,
                    R.string.step_wait, R.string.step_wait_desc,
                    StepType.WAIT, false
                ),
                Row(
                    R.drawable.ic_step_ocr,
                    R.string.step_ocr_read, R.string.step_ocr_read_desc,
                    StepType.OCR_READ, false
                ),
                Row(
                    R.drawable.ic_step_code,
                    R.string.step_type_code, R.string.step_type_code_desc,
                    StepType.TYPE_CODE, false
                ),
                Row(
                    R.drawable.ic_step_excel,
                    R.string.step_type_excel_row, R.string.step_type_excel_row_desc,
                    StepType.TYPE_EXCEL_ROW, false
                )
            )
        ),
        Section(
            R.string.add_group_advanced,
            listOf(
                Row(
                    R.drawable.ic_step_screenshot,
                    R.string.step_screenshot, R.string.step_screenshot_desc,
                    StepType.SCREENSHOT, false
                ),
                Row(
                    R.drawable.ic_step_paste,
                    R.string.step_paste, R.string.step_paste_desc,
                    StepType.PASTE, false
                ),
                Row(
                    R.drawable.ic_step_vibration,
                    R.string.step_vibration, R.string.step_vibration_desc,
                    StepType.VIBRATION, false
                ),
                Row(
                    R.drawable.ic_step_sound,
                    R.string.step_sound, R.string.step_sound_desc,
                    StepType.SOUND, false
                ),
                Row(
                    R.drawable.ic_step_screenoff,
                    R.string.step_screen_off, R.string.step_screen_off_desc,
                    StepType.SCREEN_OFF, false
                ),
                Row(
                    R.drawable.ic_step_area,
                    R.string.step_area_click, R.string.step_area_click_desc,
                    StepType.AREA_CLICK, false
                ),
                Row(
                    R.drawable.ic_step_stop,
                    R.string.step_stop_task, R.string.step_stop_task_desc,
                    StepType.STOP_TASK, false
                ),
                Row(
                    R.drawable.ic_step_intent,
                    R.string.step_intent_uri, R.string.step_intent_uri_desc,
                    StepType.INTENT_URI, false
                ),
                Row(
                    R.drawable.ic_step_network,
                    R.string.step_network_get, R.string.step_network_get_desc,
                    StepType.NETWORK_GET, false
                ),
                Row(
                    R.drawable.ic_step_slide,
                    R.string.step_slide_list, R.string.step_slide_list_desc,
                    StepType.SLIDE_LIST, false
                ),
                Row(
                    R.drawable.ic_step_qs,
                    R.string.step_qs_tile, R.string.step_qs_tile_desc,
                    StepType.QS_TILE, false
                ),
                Row(
                    R.drawable.ic_step_power,
                    R.string.step_power_dialog, R.string.step_power_dialog_desc,
                    StepType.POWER_DIALOG, false
                ),
                Row(
                    R.drawable.ic_step_loop,
                    R.string.step_loop, R.string.step_loop_desc,
                    StepType.LOOP_ACTION, false
                )
            )
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.bottom_sheet_add_step, container, false)

        val list = view.findViewById<RecyclerView>(R.id.add_step_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = AddStepAdapter(sections, showRecordRow) { row ->
            if (row.isRecord) {
                onRecordRequested?.invoke()
            } else {
                row.type?.let { onStepChosenListener?.onStepChosen(it) }
            }
            dismiss()
        }

        view.findViewById<Button>(R.id.btn_close_sheet).setOnClickListener {
            dismiss()
        }
        return view
    }

    // Flattened adapter items: one header item per section, then its rows.
    private sealed interface ListItem {
        data class Group(val titleRes: Int) : ListItem
        data class StepRow(val row: Row) : ListItem
    }

    private class AddStepAdapter(
        sections: List<Section>,
        private val showRecordRow: Boolean,
        private val onRowClicked: (Row) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val VIEW_TYPE_GROUP = 0
            private const val VIEW_TYPE_ROW = 1
        }

        private val items: List<ListItem> = buildList {
            sections.forEach { section ->
                val rows = if (showRecordRow) section.rows
                    else section.rows.filter { !it.isRecord }
                if (rows.isEmpty()) return@forEach
                add(ListItem.Group(section.titleRes))
                rows.forEach { row -> add(ListItem.StepRow(row)) }
            }
        }

        class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val groupTitle: TextView = view.findViewById(R.id.group_title)
        }

        class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val rowIcon: ImageView = view.findViewById(R.id.row_icon)
            val rowTitle: TextView = view.findViewById(R.id.row_title)
            val rowDesc: TextView = view.findViewById(R.id.row_desc)
            val rowBadge: TextView = view.findViewById(R.id.row_badge)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is ListItem.Group -> VIEW_TYPE_GROUP
            is ListItem.StepRow -> VIEW_TYPE_ROW
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_GROUP) {
                GroupViewHolder(
                    inflater.inflate(R.layout.item_add_step_group, parent, false)
                )
            } else {
                RowViewHolder(
                    inflater.inflate(R.layout.item_add_step_row, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Group -> {
                    (holder as GroupViewHolder).groupTitle.setText(item.titleRes)
                }
                is ListItem.StepRow -> {
                    val rowHolder = holder as RowViewHolder
                    rowHolder.rowIcon.setImageResource(item.row.iconRes)
                    rowHolder.rowTitle.setText(item.row.titleRes)
                    rowHolder.rowDesc.setText(item.row.descRes)
                    rowHolder.rowBadge.visibility =
                        if (item.row.isNew) View.VISIBLE else View.GONE
                    rowHolder.itemView.setOnClickListener { onRowClicked(item.row) }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
