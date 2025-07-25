package com.example.splitterrr.ui.expense

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.splitterrr.R
import com.example.splitterrr.databinding.FragmentExpenseListBinding
import com.example.splitterrr.utils.ExpenseViewModelFactory
import com.example.splitterrr.utils.LocationHelper
import com.example.splitterrr.viewmodel.ExpenseViewModel
import java.util.Locale

class ExpenseListFragment : Fragment() {

    private lateinit var locationHelper: LocationHelper
    private var _binding: FragmentExpenseListBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: ExpenseViewModel
    private lateinit var adapter: ExpenseAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExpenseListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        locationHelper = LocationHelper(requireContext())

        // Observe LiveData and update UI
        viewModel = ViewModelProvider(this,
            ExpenseViewModelFactory(requireActivity().application))[ExpenseViewModel::class.java]

        adapter = ExpenseAdapter(requireContext(),viewModel)
        binding.recyclerViewExpenses.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewExpenses.adapter = adapter

        viewModel.allExpenses.observe(viewLifecycleOwner) {
            adapter.submitList(it)
        }


        binding.fabAddExpense.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, AddExpenseFragment())
                .addToBackStack(null)
                .commit()
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}