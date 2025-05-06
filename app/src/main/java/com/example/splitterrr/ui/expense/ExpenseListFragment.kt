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

        binding.location.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            binding.locationTxv.text = ""
            getCurrentLocation(false)
        }

        binding.location2.setOnClickListener {
            binding.progressBar.visibility = View.VISIBLE
            binding.location1Txv.text = ""
            getCurrentLocation(true)
        }

        binding.location1Txv.setOnClickListener {
            try {
                val coords = extractLatLngFromText(binding.location1Txv.text.toString())
                coords?.let { (lat, lng) -> openInGoogleMaps(lat, lng) }
            }catch (e: Exception){
                e.printStackTrace()
            }

        }

        binding.locationTxv.setOnClickListener {
            try{
                val coords = extractLatLngFromText(binding.locationTxv.text.toString())
                coords?.let { (lat, lng) -> openInGoogleMaps(lat, lng) }
            }catch (e: Exception){
                e.printStackTrace()
            }
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun getCurrentLocation(accuracy: Boolean){
        locationHelper.getCurrentLocation(accuracy) {  result ->
            when (result) {
                is LocationHelper.LatestLocationResult.Success -> {
                    // Successfully got the location
                    val lat = result.lat
                    val lng = result.lng
                    val accuracy1 = result.accuracy
                    if(accuracy){
                        binding.location1Txv.text = "Location with High Accuracy --> Lat: $lat, Lng: $lng"
                    }else{
                        binding.locationTxv.text = "Location --> Lat: $lat, Lng: $lng"
                    }
                    Log.d("Location", "Lat: $lat, Lng: $lng")
                    binding.progressBar.visibility = View.GONE
                }
                LocationHelper.LatestLocationResult.PermissionDenied -> {
                    // Handle permission denied error
                    Toast.makeText(requireContext(), "Permission denied. Please enable location permission.", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
                LocationHelper.LatestLocationResult.GPSEnabledRequired -> {
                    // Handle GPS disabled error
                    Toast.makeText(requireContext(), "GPS is off. Please enable GPS.", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
                LocationHelper.LatestLocationResult.LocationUnavailable -> {
                    // Handle location unavailable error
                    Toast.makeText(requireContext(), "Unable to get location. Please try again.", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                }
            }
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun extractLatLngFromText(text: String): Pair<String, String>? {
        try {
            val regex = Regex("Lat: ([\\d.-]+), Lng: ([\\d.-]+)")
            val match = regex.find(text)
            return if (match != null && match.groupValues.size == 3) {
                val lat = match.groupValues[1]
                val lng = match.groupValues[2]
                if (lat != null && lng != null) Pair(lat, lng) else null
            } else null
        }catch (e: Exception){
            e.printStackTrace()
            return null
        }
    }

    private fun openInGoogleMaps(lat: String, lng: String) {
        if (!TextUtils.isEmpty(lat) && !TextUtils.isEmpty(lng)) {
            try {
                val uri = String.format(
                    Locale.ENGLISH, "%s,%s",
                    lat, lng
                )
                val map = "http://maps.google.co.in/maps?q=$uri&zoom=10"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(map))
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}