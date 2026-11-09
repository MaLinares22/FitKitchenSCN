package com.uth.fitkitchenscn.model

import android.graphics.Rect

data class IngredienteInfo(
    val nombre: String = "",
    val categoria: String = "",
    val calorias_100g: Int = 0,
    val nota_rapida: String = ""
)

data class Receta(
    val titulo: String = "",
    val objetivo_gym: String = "",
    val tiempo_prep: String = "",
    val ingredientes_clave: List<String> = emptyList(),
    val pasos: String = "",
    val macros: Macros = Macros(),
    val semaforo_clinico: SemaforoClinico = SemaforoClinico()
)

data class Macros(
    val calorias: Int = 0,
    val proteinas: String = "",
    val carbohidratos: String = "",
    val grasas: String = ""
)

data class SemaforoClinico(
    val diabeticos: PerfilMedico = PerfilMedico(),
    val hipertensos: PerfilMedico = PerfilMedico(),
    val celiacos: PerfilMedico = PerfilMedico()
)

data class PerfilMedico(
    val estado: String = "APTO",
    val color: String = "VERDE",
    val recomendacion: String = ""
)

data class DetectedBox(
    val rect: Rect,
    val label: String,
    val confidence: Float,
    val trackingId: Int? = null
)
