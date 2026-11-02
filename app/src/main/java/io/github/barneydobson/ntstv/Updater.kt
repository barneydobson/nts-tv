package io.github.barneydobson.ntstv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** A newer release that has been downloaded and checked, ready to install. */
class PreparedUpdate(val version: String, val apk: File)

sealed interface UpdateState {
    data object None : UpdateState
    data class Ready(val update: PreparedUpdate) : UpdateState
    data class Installing(val update: PreparedUpdate) : UpdateState
    data class Failed(val update: PreparedUpdate) : UpdateState
}

/**
 * Keeps the app up to date from this project's GitHub releases.
 *
 * Only release-signed builds update themselves. A downloaded APK is installed only if it is
 * this app, a newer version, and signed with the release certificate. Android independently
 * refuses any update that isn't signed with the same key as the installed app.
 */
object Updater {
    const val TAG = "NtsTvUpdater"

    private const val REPO = "barneydobson/nts-tv"
    private const val LATEST_URL = "https://github.com/$REPO/releases/latest"
    private const val TAG_PATH_PREFIX = "/$REPO/releases/tag/"
    private const val APK_NAME = "nts-tv.apk"
    private val TAG_PATTERN = Regex("""v(\d{1,4})\.(\d{1,4})\.(\d{1,4})""")

    /** SHA-256 of the release signing certificate (also published in SECURITY.md). */
    private const val RELEASE_CERT_SHA256 =
        "b2935341ac25ad94c218f4bfcaefd1b9a12a62e7c07d3fcb0ff63907f1000021"

    private const val MAX_APK_BYTES = 50L * 1024 * 1024
    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state

    private var lastCheck = 0L

    /**
     * Looks for a newer release (at most hourly) and downloads it.
     * Returns the update once it's ready to install, or null if there's nothing to do.
     */
    suspend fun check(context: Context): PreparedUpdate? {
        when (val current = _state.value) {
            is UpdateState.Ready -> return current.update
            is UpdateState.Installing, is UpdateState.Failed -> return null
            UpdateState.None -> Unit
        }
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < CHECK_INTERVAL_MS) return null
        lastCheck = now

        return try {
            withContext(Dispatchers.IO) {
                if (!isReleaseBuild(context)) {
                    Log.i(TAG, "Updates are off: this isn't a release-signed build")
                    null
                } else {
                    prepareUpdate(context)
                }
            }?.also { _state.value = UpdateState.Ready(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Hands the update to Android's package installer. The app is restarted by the update. */
    suspend fun install(context: Context, update: PreparedUpdate) {
        _state.value = UpdateState.Installing(update)
        try {
            withContext(Dispatchers.IO) { commitSession(context, update.apk) }
            Log.i(TAG, "Installing ${update.version}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the install: ${e.message}")
            _state.value = UpdateState.Failed(update)
        }
    }

    /** Called by [UpdateReceiver] when Android reports that the install didn't happen. */
    internal fun onInstallFailed() {
        (_state.value as? UpdateState.Installing)?.let { _state.value = UpdateState.Failed(it.update) }
    }

    private fun isReleaseBuild(context: Context): Boolean =
        RELEASE_CERT_SHA256 in certificateHashes(installedInfo(context))

    private fun prepareUpdate(context: Context): PreparedUpdate? {
        val tag = latestReleaseTag()
        val latest = parseVersion(tag) ?: throw IOException("Unexpected release tag '$tag'")
        val installed = parseVersion("v" + BuildConfig.VERSION_NAME.substringBefore('-'))
        val version = tag.removePrefix("v")
        if (installed != null && compareVersions(latest, installed) <= 0) {
            Log.i(TAG, "Up to date (installed ${BuildConfig.VERSION_NAME}, latest release $version)")
            return null
        }

        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, APK_NAME)
        download("https://github.com/$REPO/releases/download/$tag/$APK_NAME", apk)
        verify(context, apk)?.let { problem ->
            apk.delete()
            throw IOException("Rejected the $version download: $problem")
        }
        Log.i(TAG, "Update $version downloaded and verified")
        return PreparedUpdate(version, apk)
    }

    /** github.com/…/releases/latest redirects to the newest release's tag page. */
    private fun latestReleaseTag(): String {
        val connection = open(LATEST_URL)
        connection.instanceFollowRedirects = false
        try {
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (code !in 300..399 || location == null) throw IOException("HTTP $code from $LATEST_URL")
            val target = URI(LATEST_URL).resolve(location)
            if (target.scheme != "https" || target.host != "github.com" ||
                !target.path.startsWith(TAG_PATH_PREFIX)
            ) {
                throw IOException("Unexpected redirect to $location")
            }
            return target.path.removePrefix(TAG_PATH_PREFIX)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        val connection = open(url) // Follows the HTTPS redirect to GitHub's download host.
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code downloading the update")
            val host = connection.url.host
            if (connection.url.protocol != "https" ||
                (host != "github.com" && !host.endsWith(".githubusercontent.com"))
            ) {
                throw IOException("Unexpected download host $host")
            }
            if (connection.contentLengthLong > MAX_APK_BYTES) throw IOException("Update is too large")
            connection.inputStream.use { input -> target.outputStream().use { copyCapped(input, it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun verify(context: Context, apk: File): String? {
        val info = archiveInfo(context.packageManager, apk.path) ?: return "not a valid APK"
        if (info.packageName != context.packageName) return "it's a different app (${info.packageName})"
        val installedVersion = PackageInfoCompat.getLongVersionCode(installedInfo(context))
        if (PackageInfoCompat.getLongVersionCode(info) <= installedVersion) return "it isn't newer"
        val certificates = certificateHashes(info)
        // Some Android versions don't report an archive's signer; Android still enforces it.
        if (certificates.isNotEmpty() && RELEASE_CERT_SHA256 !in certificates) {
            return "it's signed with an unexpected certificate"
        }
        return null
    }

    private fun commitSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets self-updates go through without a prompt once the user has allowed
                // this app to install updates.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val status = Intent(context, UpdateReceiver::class.java)
                    .setAction(UpdateReceiver.ACTION_INSTALL_STATUS)
                // Mutable because the installer adds the result to it; the intent is explicit.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, status, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", NtsApi.USER_AGENT)
        }

    private fun copyCapped(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_APK_BYTES) throw IOException("Update is too large")
            output.write(buffer, 0, read)
        }
    }

    private fun parseVersion(tag: String): List<Int>? =
        TAG_PATTERN.matchEntire(tag)?.groupValues?.drop(1)?.map { it.toInt() }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int =
        a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0

    @Suppress("DEPRECATION")
    private val SIGNATURE_FLAGS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(context: Context): PackageInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(SIGNATURE_FLAGS.toLong()))
        } else {
            pm.getPackageInfo(context.packageName, SIGNATURE_FLAGS)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(SIGNATURE_FLAGS.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, SIGNATURE_FLAGS)
        }

    @Suppress("DEPRECATION")
    private fun certificateHashes(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
                }
            } else {
                info.signatures
            }
        return signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
