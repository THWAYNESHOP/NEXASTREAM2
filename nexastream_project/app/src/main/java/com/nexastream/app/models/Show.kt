package com.nexastream.app.models

import com.nexastream.app.adapters.AppAdapter
import java.util.Calendar

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
    var id: String
    var title: String
    var overview: String?
    var released: Calendar?
    var runtime: Int?
    var trailer: String?
    var quality: String?
    var rating: Double?
    var poster: String?
    var banner: String?
    var imdbId: String?
    var providerName: String?
}
