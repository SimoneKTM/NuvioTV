package com.nuvio.tv.data.remote.supabase

import android.util.Log
import com.nuvio.tv.BuildConfig
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Inject
import javax.inject.Singleton

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null
)

@Singleton
class AvatarRepository @Inject constructor(
    private val postgrest: Postgrest
) {
    private var cachedCatalog: List<AvatarCatalogItem>? = null

    suspend fun getAvatarCatalog(): List<AvatarCatalogItem> {
        cachedCatalog?.let { return it }

        try {
            val response = postgrest.rpc("get_avatar_catalog")
            val remote = response.decodeList<SupabaseAvatarCatalogItem>()
            Log.d(TAG, "RPC returned ${remote.size} avatar items")
            val catalog = remote.map { item ->
                AvatarCatalogItem(
                    id = item.id,
                    displayName = item.displayName,
                    imageUrl = avatarImageUrl(item.storagePath),
                    category = item.category,
                    sortOrder = item.sortOrder,
                    bgColor = item.bgColor
                )
            }
            cachedCatalog = catalog
            return catalog
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load avatar catalog from Supabase, using fallback", e)
            val fallback = fallbackAvatarCatalog()
            cachedCatalog = fallback
            return fallback
        }
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
    }

    fun invalidateCache() {
        cachedCatalog = null
    }

    companion object {
        private const val TAG = "AvatarRepository"

        fun avatarImageUrl(storagePath: String): String {
            if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) return storagePath
            val configured = BuildConfig.AVATAR_PUBLIC_BASE_URL.trimEnd('/')
            val baseUrl = configured.ifBlank {
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/storage/v1/object/public/avatars"
            }
            return if (baseUrl.isNotEmpty()) "$baseUrl/$storagePath" else storagePath
        }

        private fun fallbackAvatarCatalog(): List<AvatarCatalogItem> = listOf(
            AvatarCatalogItem(id="av1",  displayName="Red",     imageUrl="", category="color", sortOrder=1,  bgColor="#E53935"),
            AvatarCatalogItem(id="av2",  displayName="Blue",    imageUrl="", category="color", sortOrder=2,  bgColor="#1E88E5"),
            AvatarCatalogItem(id="av3",  displayName="Green",   imageUrl="", category="color", sortOrder=3,  bgColor="#43A047"),
            AvatarCatalogItem(id="av4",  displayName="Purple",  imageUrl="", category="color", sortOrder=4,  bgColor="#8E24AA"),
            AvatarCatalogItem(id="av5",  displayName="Orange",  imageUrl="", category="color", sortOrder=5,  bgColor="#FB8C00"),
            AvatarCatalogItem(id="av6",  displayName="Teal",    imageUrl="", category="color", sortOrder=6,  bgColor="#00897B"),
            AvatarCatalogItem(id="av7",  displayName="Pink",    imageUrl="", category="color", sortOrder=7,  bgColor="#D81B60"),
            AvatarCatalogItem(id="av8",  displayName="Indigo",  imageUrl="", category="color", sortOrder=8,  bgColor="#3949AB"),
            AvatarCatalogItem(id="av9",  displayName="Cyan",    imageUrl="", category="color", sortOrder=9,  bgColor="#00ACC1"),
            AvatarCatalogItem(id="av10", displayName="Brown",   imageUrl="", category="color", sortOrder=10, bgColor="#6D4C41"),
            AvatarCatalogItem(id="av11", displayName="Lime",    imageUrl="", category="color", sortOrder=11, bgColor="#7CB342"),
            AvatarCatalogItem(id="av12", displayName="Deep Orange", imageUrl="", category="color", sortOrder=12, bgColor="#F4511E")
        )
    }
}
