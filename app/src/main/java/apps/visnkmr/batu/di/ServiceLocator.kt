package apps.visnkmr.batu.di

import android.content.Context
import okhttp3.OkHttpClient
import apps.visnkmr.batu.data.AppDatabase
import apps.visnkmr.batu.data.ChatRepository

/**
 * Lightweight singleton provider for app-wide services.
 * Replace with Hilt later if desired.
 */
object ServiceLocator {

  @Volatile
  private var okHttpClient: OkHttpClient? = null

  @Volatile
  private var appDatabase: AppDatabase? = null

  @Volatile
  private var chatRepository: ChatRepository? = null

  fun provideOkHttpClient(): OkHttpClient {
    // Double-checked locking for thread-safety
    var local = okHttpClient
    if (local == null) {
      synchronized(this) {
        local = okHttpClient
        if (local == null) {
          local = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
          okHttpClient = local
        }
      }
    }
    return local!!
  }

  fun provideDatabase(context: Context): AppDatabase {
    var local = appDatabase
    if (local == null) {
      synchronized(this) {
        local = appDatabase
        if (local == null) {
          local = AppDatabase.get(context.applicationContext)
          appDatabase = local
        }
      }
    }
    return local!!
  }

  fun provideChatRepository(context: Context): ChatRepository {
    var local = chatRepository
    if (local == null) {
      synchronized(this) {
        local = chatRepository
        if (local == null) {
          val db = provideDatabase(context)
          local = ChatRepository(db.conversationDao(), db.messageDao())
          chatRepository = local
        }
      }
    }
    return local!!
  }

  // Optional helpers to clear for tests
  fun resetForTests() {
    synchronized(this) {
      okHttpClient = null
      appDatabase = null
      chatRepository = null
    }
  }
}
