package com.example.splitterrr.ui.expense

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.splitterrr.data.local.ExpenseEntity
import com.example.splitterrr.databinding.ItemExpenseBinding
import com.example.splitterrr.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExpenseAdapter(private val context: Context,private val viewModel: ExpenseViewModel) : RecyclerView.Adapter<ExpenseAdapter.ExpenseViewHolder>() {

    private var expenses: List<ExpenseEntity> = listOf()

    fun submitList(newList: List<ExpenseEntity>) {
        expenses = newList
        notifyDataSetChanged()
    }

    inner class ExpenseViewHolder(val binding: ItemExpenseBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(expense: ExpenseEntity) {
            binding.textViewDescription.text = expense.description
            binding.textViewAmount.text = "₹${expense.amount}"
            try {
                binding.textViewDate.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                    .format(Date(expense.date))
            }catch (e: Exception){
                e.printStackTrace()
            }

            binding.delete.setOnClickListener {
                viewModel.delete(expense)
            }

        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExpenseViewHolder {
        val binding = ItemExpenseBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ExpenseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExpenseViewHolder, position: Int) {
        holder.bind(expenses[position])
    }

    override fun getItemCount() = expenses.size
}
