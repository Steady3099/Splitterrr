package com.example.splitterrr.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.splitterrr.data.local.ExpenseDatabase
import com.example.splitterrr.data.local.ExpenseEntity
import com.example.splitterrr.data.repository.ExpenseRepository
import kotlinx.coroutines.launch

class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ExpenseRepository
    val allExpenses: LiveData<List<ExpenseEntity>>

    init {
        val dao = ExpenseDatabase.getDatabase(application).expenseDao()
        repository = ExpenseRepository(dao)
        allExpenses = repository.allExpenses
    }

    fun insert(expense: ExpenseEntity) = viewModelScope.launch {
        repository.insert(expense)
    }

    fun delete(expense: ExpenseEntity) = viewModelScope.launch {
        repository.delete(expense)
    }
}