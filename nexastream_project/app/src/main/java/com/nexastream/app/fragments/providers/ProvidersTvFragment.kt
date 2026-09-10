package com.nexastream.app.fragments.providers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.databinding.FragmentProvidersTvBinding
import com.nexastream.app.databinding.ItemLanguageTvBinding
import com.nexastream.app.models.Provider as ModelProvider
import com.nexastream.app.providers.Provider
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.viewModelsFactory
import kotlinx.coroutines.launch
import java.util.Locale

class ProvidersTvFragment : Fragment() {

    private var _binding: FragmentProvidersTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModelsFactory { ProvidersViewModel() }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProvidersTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeProviders()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    ProvidersViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is ProvidersViewModel.State.SuccessLoading -> {
                        displayProviders(state.providers)
                        binding.rvProviders.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is ProvidersViewModel.State.FailedLoading -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener {
                                viewModel.getProviders()
                            }
                            binding.rvProviders.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeProviders() {
        val languages = Provider.providers.keys
            .distinctBy { it.language }
            .map {
                val locale = Locale.forLanguageTag(it.language)

                Language(
                    code = it.language,
                    name = locale.getDisplayLanguage(locale)
                        .replaceFirstChar { char -> char.titlecase() },
                )
            }
            .sortedBy { it.name.lowercase() }

        val allLanguages = mutableListOf(
            Language(null, getString(R.string.providers_all_languages)),
            Language("favorites", getString(R.string.providers_favorites))
        ).apply {
            addAll(languages)
        }

        val languageAdapter = LanguageAdapter(allLanguages) { language ->
            UserPreferences.providerLanguage = language.code
            viewModel.getProviders(language.code)
        }

        val initialIndex = when (val lang = UserPreferences.providerLanguage) {
            null -> 0
            "favorites" -> 1
            else -> {
                val index = languages.indexOfFirst { it.code == lang }
                if (index != -1) index + 2 else 0
            }
        }
        languageAdapter.selectedIndex = initialIndex

        binding.rvLanguages.apply {
            adapter = languageAdapter
        }

        binding.rvProviders.apply {
            setNumColumns(5)
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
        }
    }

    private data class Language(
        val code: String?,
        val name: String,
    )

    private class LanguageAdapter(
        private val languages: List<Language>,
        private val onLanguageSelected: (Language) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.ViewHolder>() {

        var selectedIndex = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemLanguageTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val language = languages[position]
            holder.binding.tvLanguageName.text = language.name
            holder.binding.root.isSelected = position == selectedIndex

            holder.binding.root.setOnClickListener {
                val oldIndex = selectedIndex
                selectedIndex = holder.layoutPosition
                notifyItemChanged(oldIndex)
                notifyItemChanged(selectedIndex)
                onLanguageSelected(language)
            }
        }

        override fun getItemCount() = languages.size

        class ViewHolder(val binding: ItemLanguageTvBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private fun displayProviders(providers: List<ModelProvider>) {
        appAdapter.submitList(providers.onEach {
            it.itemType = AppAdapter.Type.PROVIDER_TV_ITEM
        })

        binding.rvProviders.requestFocus()
    }
}
