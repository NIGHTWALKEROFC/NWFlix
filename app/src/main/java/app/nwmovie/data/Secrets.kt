package app.nwmovie.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.nwmovie.BuildConfig
import java.security.SecureRandom

/** Everything sensitive (API keys, TDLib database key) lives here, encrypted with the Android Keystore. */
object Secrets {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        sp = try { create(ctx) } catch (e: Exception) {
            ctx.deleteSharedPreferences("nw_secure")
            try { create(ctx) } catch (e2: Exception) { null }
        }
    }

    private fun create(ctx: Context, name: String = "nw_secure"): SharedPreferences {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, name, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Encrypted store for the signed-in account's session tokens. Null when the keystore is unusable. */
    fun account(ctx: Context): SharedPreferences? = try { create(ctx, "nw_account_secure") } catch (e: Exception) {
        ctx.deleteSharedPreferences("nw_account_secure")
        try { create(ctx, "nw_account_secure") } catch (e2: Exception) { null }
    }

    var tgApiId: Int
        get() = if (BuildConfig.TG_API_ID != 0) BuildConfig.TG_API_ID else (sp?.getInt("tg_id", 0) ?: 0)
        set(v) { sp?.edit()?.putInt("tg_id", v)?.apply() }

    var tgApiHash: String
        get() = BuildConfig.TG_API_HASH.ifBlank { sp?.getString("tg_hash", "") ?: "" }
        set(v) { sp?.edit()?.putString("tg_hash", v.trim())?.apply() }

    var tmdbKey: String
        get() = BuildConfig.TMDB_API_KEY.ifBlank { sp?.getString("tmdb", "") ?: "" }
        set(v) { sp?.edit()?.putString("tmdb", v.trim())?.apply() }

    val keysBuiltIn: Boolean get() = BuildConfig.TG_API_ID != 0 && BuildConfig.TG_API_HASH.isNotBlank()
    fun hasTelegramKeys() = tgApiId != 0 && tgApiHash.isNotBlank()

    /** Random 32-byte key used to encrypt the TDLib database on disk. */
    fun dbKey(): ByteArray {
        val existing = sp?.getString("db_key", null)
        if (existing != null) return Base64.decode(existing, Base64.NO_WRAP)
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp?.edit()?.putString("db_key", Base64.encodeToString(k, Base64.NO_WRAP))?.apply()
        return k
    }

    fun resetDbKey() { sp?.edit()?.remove("db_key")?.apply() }
}
