package com.nexastream.app.models

import com.nexastream.app.adapters.AppAdapter

open class Provider(
    val name: String,
    val logo: String,
    val language: String,

    val provider: com.nexastream.app.providers.Provider,
    var isFavorite: Boolean = false,
) : AppAdapter.Item {


    override lateinit var itemType: AppAdapter.Type
}
