package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.SessionStore
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.SheetAiMushairaBinding
import com.unrealstar.live.models.CreateMomentRequest
import com.unrealstar.live.models.FeedPost
import kotlinx.coroutines.launch

class AiMushairaBottomSheet(
    private val onPoemPublished: ((FeedPost) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetAiMushairaBinding? = null
    private val binding get() = _binding!!

    private var selectedCategory = "Love poem"
    private var selectedLang = "English"

    private val poemBank = mapOf(
        "Love poem" to listOf(
            "In shadows deep where whispered dreams ignite,\nThe silver moon commands the quiet night.\nEach heartbeat echoes through the silent skies,\nUnveiling wonder in your shining eyes.",
            "Across the oceans where the breezes sigh,\nA melody ascends into the sky.\nNo distance weakens what our spirits hold,\nA timeless story written in pure gold."
        ),
        "Ghazal" to listOf(
            "Roshni chaand ki chehre pe bikhar jaati hai,\nTere aane se faza aur sanwar jaati hai.\nDil ki deewar pe dastak si hui hai ab ke,\nZindagi muskura kar paas utar jaati hai.",
            "Gham ki aandhi mein bhi ek deep jala rakha hai,\nHumne umeed ka gulshan sa saja rakha hai.\nHar sitam hans ke saha tere tasawwur ke sabab,\nApne daman mein sitaron ko chupa rakha hai."
        ),
        "Funny poem" to listOf(
            "I tried to cook a gourmet stew,\nAnd almost burned the kitchen too!\nThe smoke alarm began to sing,\nNow pizza delivery is king!",
            "My alarm clock rings at break of day,\nI gently push that thing away.\nFive minutes more, I told my brain,\nNow three hours passed—late again!"
        ),
        "Ode" to listOf(
            "O radiant sun that crowns the morning crest,\nAwakening hope inside the seeker's chest.\nYour golden rays bestow the world its song,\nA symphony where dreamers all belong.",
            "O starry vault of mysteries untold,\nWhere cosmic tapestry and tales unfold.\nGuide our wandering steps into the light,\nMajestic ruler of the eternal night."
        ),
        "Shayari" to listOf(
            "Sitaron se aage jahan aur bhi hain,\nAbhi ishq ke imtihan aur bhi hain.\nTu shaheen hai parwaaz hai kaam tera,\nTere samne aasmaan aur bhi hain.",
            "Khudi ko kar buland itna ki har taqdeer se pehle,\nKhuda bande se khud pooche bata teri raza kya hai."
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetAiMushairaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCloseMushaira.setOnClickListener {
            dismiss()
        }

        setupLanguageSelector()
        setupCategories()
        setupGenerator()
        setupPublishAction()
    }

    private fun setupLanguageSelector() {
        val languages = listOf("English", "Hindi", "Urdu")
        var langIndex = 0

        binding.tvPoemLanguage.setOnClickListener {
            langIndex = (langIndex + 1) % languages.size
            selectedLang = languages[langIndex]
            binding.tvPoemLanguage.text = "$selectedLang ▾"
            generatePoem()
        }
    }

    private fun setupCategories() {
        val categories = listOf(
            binding.chipLovePoem to "Love poem",
            binding.chipGhazal to "Ghazal",
            binding.chipFunnyPoem to "Funny poem",
            binding.chipOde to "Ode",
            binding.chipShayari to "Shayari"
        )

        fun updateChips(active: String) {
            selectedCategory = active
            categories.forEach { (chip, cat) ->
                if (cat == active) {
                    chip.setBackgroundResource(R.drawable.bg_chip_active)
                } else {
                    chip.setBackgroundResource(R.drawable.bg_chip_translucent)
                }
            }
            binding.tvPoemTag.text = "#${active.replace(" ", "")} #Mushaira"
            generatePoem()
        }

        categories.forEach { (chip, cat) ->
            chip.setOnClickListener {
                updateChips(cat)
            }
        }
    }

    private fun setupGenerator() {
        binding.btnGenerateVerse.setOnClickListener {
            generatePoem()
        }
    }

    private fun generatePoem() {
        val prompt = binding.etPoemPrompt.text.toString().trim()
        val verses = poemBank[selectedCategory] ?: poemBank["Love poem"]!!
        val baseVerse = verses.random()

        val finalVerse = if (prompt.isNotEmpty()) {
            "$baseVerse\n\n[Inspired by: \"$prompt\"]"
        } else {
            baseVerse
        }

        binding.tvGeneratedVerse.text = finalVerse
        binding.tvPoemAuthor.text = "— AI Mushaira ($selectedLang) feat. Creator #${SessionStore.userId}"
    }

    private fun setupPublishAction() {
        binding.btnPostPoemToFeed.setOnClickListener {
            val verse = binding.tvGeneratedVerse.text.toString().trim()
            val tag = binding.tvPoemTag.text.toString().trim()

            binding.btnPostPoemToFeed.isEnabled = false
            binding.btnPostPoemToFeed.text = "Publishing Verse..."
            binding.btnPostPoemToFeed.alpha = 0.7f

            lifecycleScope.launch {
                val req = CreateMomentRequest(
                    media_url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    caption = "✨ $tag\n\n$verse",
                    duration_secs = 30,
                    cover_url = "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=600",
                    tags = listOf("#Mushaira", "#Poetry", "#${selectedCategory.replace(" ", "")}")
                )

                val res = UnrealStarApiClient.createMoment(req)
                res.onSuccess { post ->
                    Toast.makeText(requireContext(), "🎉 Verse published to feed!", Toast.LENGTH_SHORT).show()
                    onPoemPublished?.invoke(post)
                    dismiss()
                }.onFailure { err ->
                    binding.btnPostPoemToFeed.isEnabled = true
                    binding.btnPostPoemToFeed.text = "🚀 Post Verse to Moments"
                    binding.btnPostPoemToFeed.alpha = 1.0f
                    Toast.makeText(requireContext(), "Error: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AiMushairaBottomSheet"
    }
}
