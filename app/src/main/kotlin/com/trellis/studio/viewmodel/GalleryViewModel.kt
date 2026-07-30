package com.trellis.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.data.db.AppDatabase
import com.trellis.studio.data.entity.GenerationEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val allGenerations: StateFlow<List<GenerationEntity>> = db.generationDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val images: StateFlow<List<GenerationEntity>> = db.generationDao().getByType("image")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val models3d: StateFlow<List<GenerationEntity>> = db.generationDao().getByType("3d")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun delete(item: GenerationEntity) = viewModelScope.launch {
        item.imagePath?.let { File(it).delete() }
        item.modelPath?.let { File(it).delete() }
        db.generationDao().delete(item.id)
    }
}
