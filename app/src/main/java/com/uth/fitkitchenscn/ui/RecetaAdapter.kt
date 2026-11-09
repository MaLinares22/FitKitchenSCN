package com.uth.fitkitchenscn.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.uth.fitkitchenscn.R
import com.uth.fitkitchenscn.databinding.ItemRecetaBinding
import com.uth.fitkitchenscn.model.PerfilMedico
import com.uth.fitkitchenscn.model.Receta

class RecetaAdapter : RecyclerView.Adapter<RecetaAdapter.ViewHolder>() {
    private var items: List<Pair<Receta, Int>> = emptyList()

    fun submitList(value: List<Pair<Receta, Int>>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemRecetaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position].first)

    override fun getItemCount() = items.size

    class ViewHolder(private val binding: ItemRecetaBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(recipe: Receta) {
            binding.recipeTitle.text = recipe.titulo
            binding.recipeGoal.text = "Objetivo: ${recipe.objetivo_gym.ifBlank { "Fitness" }}"
            binding.recipeTime.text = "⏱ ${recipe.tiempo_prep}"
            binding.calories.text = "${recipe.macros.calorias} kcal"
            binding.proteins.text = recipe.macros.proteinas
            binding.carbs.text = recipe.macros.carbohidratos
            binding.fats.text = recipe.macros.grasas
            setProfile(binding.diabetesIndicator, binding.diabetesText, recipe.semaforo_clinico.diabeticos)
            setProfile(binding.hypertensionIndicator, binding.hypertensionText, recipe.semaforo_clinico.hipertensos)
            setProfile(binding.celiacIndicator, binding.celiacText, recipe.semaforo_clinico.celiacos)
        }

        private fun setProfile(indicator: View, text: android.widget.TextView, profile: PerfilMedico) {
            indicator.setBackgroundColor(
                when (profile.color.uppercase()) {
                    "ROJO" -> Color.rgb(198, 40, 40)
                    "AMARILLO" -> Color.rgb(249, 168, 37)
                    else -> Color.rgb(46, 125, 50)
                }
            )
            text.text = "${profile.estado}: ${profile.recomendacion}"
        }
    }
}
