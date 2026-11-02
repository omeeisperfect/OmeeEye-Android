package com.nimu.omeeeye.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.RunHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Run history, newest first. Tap a row to expand/collapse its
 * step-by-step detail (feature 11): step number + label + duration in
 * ms + status per step, and the clean error reason on failure.
 * Records written before detail existed have no step logs; those expand
 * to a short "No step detail" line — never a crash, never fake steps.
 * Read-only: no actions on a row.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var historyList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyList = findViewById(R.id.history_list)
        emptyText = findViewById(R.id.empty_text)
        adapter = HistoryAdapter()
        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val records = RunHistoryStore.loadAll(this)
            .sortedByDescending { it.timeMs }
        adapter.submitList(records)
        emptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        historyList.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
    }

    private class HistoryAdapter :
        RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private val dateFormat =
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        private var items: List<RunHistoryStore.RunRecord> = emptyList()

        /** Positions currently expanded. Cleared on every reload. */
        private val expanded = mutableSetOf<Int>()

        fun submitList(records: List<RunHistoryStore.RunRecord>) {
            items = records
            expanded.clear()
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val historyName: TextView = view.findViewById(R.id.history_name)
            val historyTime: TextView = view.findViewById(R.id.history_time)
            val historyStatus: TextView = view.findViewById(R.id.history_status)
            val detailContainer: LinearLayout =
                view.findViewById(R.id.history_detail_container)
            val noDetail: TextView = view.findViewById(R.id.history_no_detail)
            val stepLogsContainer: LinearLayout =
                view.findViewById(R.id.history_step_logs)
            val historyError: TextView = view.findViewById(R.id.history_error)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val record = items[position]
            holder.historyName.text = record.workflowName
            holder.historyTime.text = dateFormat.format(Date(record.timeMs))
            holder.historyStatus.text =
                record.status + ": " + record.stepsDone + "/" + record.totalSteps

            val isExpanded = expanded.contains(position)
            holder.detailContainer.visibility =
                if (isExpanded) View.VISIBLE else View.GONE
            if (isExpanded) {
                // Rebuild the detail views each bind: rows are recycled,
                // so stale step lines from another record must not linger.
                holder.stepLogsContainer.removeAllViews()
                if (record.stepLogs.isEmpty()) {
                    holder.noDetail.visibility = View.VISIBLE
                    holder.stepLogsContainer.visibility = View.GONE
                } else {
                    holder.noDetail.visibility = View.GONE
                    holder.stepLogsContainer.visibility = View.VISIBLE
                    val inflater = LayoutInflater.from(holder.itemView.context)
                    for (log in record.stepLogs) {
                        val row = inflater.inflate(
                            R.layout.item_history_detail_row,
                            holder.stepLogsContainer,
                            false
                        )
                        val line: TextView = row.findViewById(R.id.detail_line)
                        val error: TextView = row.findViewById(R.id.detail_error)
                        // Step numbers are 1-based for display; the stored
                        // stepIndex is 0-based.
                        line.text = holder.itemView.context.getString(
                            R.string.history_step_line,
                            log.stepIndex + 1,
                            log.label,
                            log.durationMs,
                            log.status
                        )
                        if (log.error.isNotBlank()) {
                            error.text = holder.itemView.context.getString(
                                R.string.history_error_line, log.error
                            )
                            error.visibility = View.VISIBLE
                        } else {
                            error.visibility = View.GONE
                        }
                        holder.stepLogsContainer.addView(row)
                    }
                }
                if (record.error.isNotBlank()) {
                    holder.historyError.text =
                        holder.itemView.context.getString(
                            R.string.history_error_line, record.error
                        )
                    holder.historyError.visibility = View.VISIBLE
                } else {
                    holder.historyError.visibility = View.GONE
                }
            }

            holder.itemView.setOnClickListener {
                if (expanded.contains(position)) {
                    expanded.remove(position)
                } else {
                    expanded.add(position)
                }
                notifyItemChanged(position)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
