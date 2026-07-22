package com.airfreshener.telegram_sms.utils

import android.content.Context
import android.util.Log
import com.airfreshener.telegram_sms.model.ProxyConfigV2
import io.paperdb.Book
import io.paperdb.Paper
import io.paperdb.PaperDbException

object PaperUtils {

    @PublishedApi
    internal const val TAG = "PaperUtils"

    val DEFAULT_BOOK: Book
        get() = getDefaultBook()

    val SYSTEM_BOOK: Book
        get() = getSystemBook()

    fun init(context: Context): Unit = Paper.init(context)

    fun getSystemBook(): Book = Paper.book("system_config")

    fun getDefaultBook(): Book = Paper.book()

    fun getSendTempBook(): Book = Paper.book("send_temp")

    fun getProxyConfig():ProxyConfigV2 =
        getSystemBook().tryRead("proxy_config", ProxyConfigV2())

    /**
     * Reads [key], returning [def] when the key is missing OR the stored data cannot be
     * deserialized. PaperDB is not multi-process safe: concurrent writes from the :command
     * and :battery service processes can corrupt a table on disk, after which a plain read()
     * throws PaperDbException (Kryo "Buffer underflow") and crashes the caller. Here we swallow
     * that, log it, and drop the broken key so it self-heals on the next write.
     */
    inline fun <reified T> Book.tryRead(key: String, def: T): T =
        try {
            read<T>(key) ?: def
        } catch (e: PaperDbException) {
            Log.w(TAG, "Corrupted PaperDB entry for key '$key', resetting to default", e)
            try {
                delete(key)
            } catch (deleteError: PaperDbException) {
                Log.e(TAG, "Failed to delete corrupted key '$key'", deleteError)
            }
            def
        }
}
