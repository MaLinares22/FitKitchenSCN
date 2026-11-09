package com.uth.fitkitchenscn.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.uth.fitkitchenscn.model.IngredienteInfo
import com.uth.fitkitchenscn.model.Receta
import kotlinx.coroutines.tasks.await

class FirebaseRepository {
    companion object {
        val INGREDIENTES_VALIDOS = listOf(
            "huevo", "atun", "limon",
            "mayonesa", "granola", "leche"
        )
    }

    private val database = FirebaseDatabase.getInstance().reference
    private var ingredientes: Map<String, IngredienteInfo> = emptyMap()
    private var recetas: List<Receta> = emptyList()

    suspend fun cargarDatos() {
        val ingredientesSnapshot = database.child("ingredientes_info").get().await()
        val recetasSnapshot = database.child("recetas").get().await()
        ingredientes = ingredientesSnapshot.toModelMap<IngredienteInfo>()
        recetas = recetasSnapshot.toModelList<Receta>()
    }

    suspend fun cargarIngredientes(): Map<String, IngredienteInfo> {
        if (ingredientes.isEmpty()) cargarDatos()
        return ingredientes
    }

    suspend fun cargarRecetas(): List<Receta> {
        if (recetas.isEmpty()) cargarDatos()
        return recetas
    }

    fun filtrarRecetas(ingredientesDetectados: Set<String>): List<Pair<Receta, Int>> {
        val detectados = ingredientesDetectados.map(::normalizar).toSet()
        return recetas.map { receta ->
            val coincidencias = receta.ingredientes_clave.count { normalizar(it) in detectados }
            receta to coincidencias
        }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    private fun normalizar(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")

    private inline fun <reified T : Any> DataSnapshot.toModelList(): List<T> =
        children.mapNotNull { it.getValue(T::class.java) }

    private inline fun <reified T : Any> DataSnapshot.toModelMap(): Map<String, T> =
        children.mapNotNull { child ->
            child.getValue(T::class.java)?.let { child.key.orEmpty() to it }
        }.toMap()
}
