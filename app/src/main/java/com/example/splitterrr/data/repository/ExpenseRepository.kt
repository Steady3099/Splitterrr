package com.example.splitterrr.data.repository

import androidx.lifecycle.LiveData
import com.example.splitterrr.data.local.ExpenseDao
import com.example.splitterrr.data.local.ExpenseEntity

class ExpenseRepository(private val dao: ExpenseDao) {
    val allExpenses: LiveData<List<ExpenseEntity>> = dao.getAllExpenses()

    suspend fun insert(expense: ExpenseEntity) {
        dao.insert(expense)
    }

    suspend fun delete(expense: ExpenseEntity) {
        dao.delete(expense)
    }
}