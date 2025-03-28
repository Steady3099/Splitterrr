package com.example.splitterrr.ui.expense

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.splitterrr.data.local.ExpenseEntity
import com.example.splitterrr.databinding.FragmentAddExpenseBinding
import com.example.splitterrr.utils.ExpenseViewModelFactory
import com.example.splitterrr.viewmodel.ExpenseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class AddExpenseFragment : Fragment() {
    private var _binding: FragmentAddExpenseBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ExpenseViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddExpenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            ExpenseViewModelFactory(requireActivity().application)
        )[ExpenseViewModel::class.java]

        binding.buttonSaveExpense.setOnClickListener {
            saveExpense()
        }
    }

    private fun saveExpense() {
        val description = binding.editTextDescription.text.toString().trim()
        val amountText = binding.editTextAmount.text.toString().trim()

        if (description.isEmpty() || amountText.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter all fields", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountText.toDouble()
        val date = Date(System.currentTimeMillis())
        val expense = ExpenseEntity(description = description, amount = amount, date = date.toString())

        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.insert(expense)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Expense added!", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}