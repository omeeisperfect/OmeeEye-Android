package com.nimu.omeeeye.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nimu.omeeeye.R
import com.nimu.omeeeye.data.RunHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Run history, newest first. Read-only: no actions on a row. */
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

        fun submitList(records: List<RunHistoryStore.RunRecord>) {
            items = records
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val historyName: TextView = view.findViewById(R.id.history_name)
            val historyTime: TextView = view.findViewById(R.id.history_time)
            val historyStatus: TextView = view.findViewById(R.id.history_status)
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
        }

        override fun getItemCount(): Int = items.size
    }
}
