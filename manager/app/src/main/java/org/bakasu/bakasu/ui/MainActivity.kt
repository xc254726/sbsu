package org.bakasu.bakasu.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bakasu.bakasu.R
import org.bakasu.bakasu.domain.model.StartupState
import org.bakasu.bakasu.domain.usecase.ApplyLanguageUseCase
import org.bakasu.bakasu.domain.usecase.EnsureManagerInstalledUseCase
import org.bakasu.bakasu.domain.usecase.ObserveStartupStateUseCase
import org.bakasu.bakasu.ui.activity.util.ThemeUtils
import org.bakasu.bakasu.ui.theme.KernelSUTheme
import org.bakasu.bakasu.ui.viewmodel.HomeUiAction
import org.bakasu.bakasu.ui.viewmodel.HomeViewModel
import org.bakasu.bakasu.ui.viewmodel.ModuleUiAction
import org.bakasu.bakasu.ui.viewmodel.ModuleViewModel
import org.bakasu.bakasu.ui.viewmodel.SettingsUiAction
import org.bakasu.bakasu.ui.viewmodel.SettingsUiEvent
import org.bakasu.bakasu.ui.viewmodel.SettingsViewModel
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModel()
    private val moduleViewModel: ModuleViewModel by viewModel()
    private val settingsViewModel: SettingsViewModel by viewModel()
    private val observeStartupState: ObserveStartupStateUseCase by inject()
    private val ensureManagerInstalled: EnsureManagerInstalledUseCase by inject()
    private val themeUtils: ThemeUtils by inject()
    private val applyLanguage: ApplyLanguageUseCase by inject()
    private val startupState by lazy { observeStartupState() }

    private var isInitialized = false

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(applyLanguage::invoke))
    }

    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            val splashScreen = installSplashScreen()

            enableEdgeToEdge()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }

            super.onCreate(savedInstanceState)

            splashScreen.setKeepOnScreenCondition {
                when (startupState.value) {
                    StartupState.Loading -> true
                    StartupState.Ready -> !homeViewModel.uiState.value.isInitialDataLoaded
                    is StartupState.Failed -> false
                }
            }

            lifecycleScope.launch {
                homeViewModel.uiState.first { it.isInitialDataLoaded }
            }

            lifecycleScope.launch { ensureManagerInstalled() }
            lifecycleScope.launch {
                settingsViewModel.events.collect { event ->
                    when (event) {
                        is SettingsUiEvent.Error -> if (event.message.isNotBlank()) {
                            Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG)
                                .show()
                        }

                        is SettingsUiEvent.Message -> {
                            val message = event.formatArg?.let {
                                getString(event.stringResource, it)
                            } ?: getString(event.stringResource)
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }

                        SettingsUiEvent.RestartActivity -> recreate()
                    }
                }
            }

            if (!isInitialized) {
                initializeData()
                isInitialized = true
            }

            if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

            setContent {
                KernelSUTheme {
                    when (val state = startupState.collectAsStateWithLifecycle().value) {
                        is StartupState.Failed -> StartupFailureContent(state.message)
                        else -> {
                            val ctx = this@MainActivity
                            var gate by remember { mutableStateOf(GateState.CHECKING) }
                            var startKey by remember { mutableStateOf("") }
                            var startPayUrl by remember { mutableStateOf<String?>(null) }

                            LaunchedEffect(Unit) {
                                val stored = License.storedKey(ctx).orEmpty()
                                startKey = stored
                                gate = if (stored.isNotBlank()) {
                                    when (val r = License.verifyKey(ctx, stored)) {
                                        is VerifyResult.Valid, is VerifyResult.NetworkError -> GateState.OK
                                        is VerifyResult.NeedPay -> {
                                            startPayUrl = r.url
                                            GateState.NEED_ACTIVATION
                                        }
                                        else -> GateState.NEED_ACTIVATION
                                    }
                                } else {
                                    GateState.NEED_ACTIVATION
                                }
                            }

                            when (gate) {
                                GateState.OK -> NavContainer(settingsViewModel, intentChannel)
                                GateState.CHECKING -> LoadingScreen()
                                GateState.NEED_ACTIVATION -> ActivationScreen(
                                    initialKey = startKey,
                                    initialPayUrl = startPayUrl,
                                    onActivate = { input, remember ->
                                        License.activate(ctx, input, remember)
                                    },
                                    onPoll = { k -> License.verifyKey(ctx, k) },
                                    onDone = { gate = GateState.OK },
                                )
                            }

                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentChannel.trySend(intent)
    }

    private fun initializeData() {
        lifecycleScope.launch {
            try {
                homeViewModel.dispatch(HomeUiAction.Refresh(showIndicator = false))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        themeUtils.initializeThemeSettings(settingsViewModel)
    }

    override fun onResume() {
        try {
            super.onResume()
            themeUtils.onActivityResume()
            synchronizeUiSettings()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun synchronizeUiSettings() {
        if (!isInitialized) return

        settingsViewModel.dispatch(SettingsUiAction.Initialize)
        moduleViewModel.dispatch(ModuleUiAction.ReloadSettings)
    }

    override fun onPause() {
        try {
            super.onPause()
            themeUtils.onActivityPause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@androidx.compose.runtime.Composable
private fun StartupFailureContent(reason: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

enum class GateState { CHECKING, OK, NEED_ACTIVATION }

sealed class VerifyResult {
    object Valid : VerifyResult()
    object Invalid : VerifyResult()
    object NetworkError : VerifyResult()
    data class NeedPay(val msg: String, val url: String) : VerifyResult()
}

sealed class ActivateResult {
    object Ok : ActivateResult()
    data class Fail(val msg: String) : ActivateResult()
    data class NeedPay(val msg: String, val url: String) : ActivateResult()
}

/**
 * 在线卡密校验
 *
 * 服务端：https://sbkey.dpdns.org (Cloudflare Worker + KV)
 *    POST /activate {key, device}
 *    POST /verify   {key, device}
 *
 * 卡密到期（欠费）时服务端返回 code=402 / need_pay=true + pay_url，
 * 客户端据此弹出收款码，付款到账后自动续期。
 */
object License {
    private const val BASE_URL = "https://sbkey.dpdns.org"
    private const val SALT = "sb-manager-v1"
    private const val PREFS = "sb_activation"
    private const val KEY = "license_key"

    private fun androidId(context: Context): String =
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "unknown"

    fun deviceCode(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$SALT|${androidId(context)}".toByteArray())
        val hex = buildString {
            for (i in 0 until 6) append(String.format(Locale.US, "%02X", bytes[i].toInt() and 0xFF))
        }
        return hex.chunked(4).joinToString("-")
    }

    fun storedKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun hasKey(context: Context): Boolean = !storedKey(context).isNullOrBlank()

    private fun saveKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, key).apply()
    }

    fun payUrl(key: String): String =
        "$BASE_URL/pay?k=" + java.net.URLEncoder.encode(key, "UTF-8")

    /** 与后台 norm() 对齐：全角转半角，保留中文/字母/数字/横线/下划线，区分大小写 */
    fun normalize(input: String): String =
        java.text.Normalizer.normalize(input.trim(), java.text.Normalizer.Form.NFKC)
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun post(path: String, body: JSONObject): JSONObject? = try {
        val conn = URL(BASE_URL + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (text.isBlank()) null else JSONObject(text)
    } catch (e: Exception) {
        null
    }

    private fun needsPay(resp: JSONObject): Boolean =
        resp.optBoolean("need_pay") || resp.optInt("code", 0) == 402 || resp.optInt("code", 0) == 426

    suspend fun activate(context: Context, input: String, remember: Boolean): ActivateResult =
        withContext(Dispatchers.IO) {
            val key = normalize(input)
            if (key.length < 2 || key.length > 40) return@withContext ActivateResult.Fail("卡密格式不对")

            val body = JSONObject().apply {
                put("key", key)
                put("device", deviceCode(context))
            }
            val resp = post("/activate", body) ?: return@withContext ActivateResult.Fail("网络连接失败，请检查网络")

            when {
                resp.optBoolean("ok") -> {
                    if (remember) saveKey(context, key)
                    ActivateResult.Ok
                }
                needsPay(resp) -> ActivateResult.NeedPay(
                    resp.optString("err", "卡密已欠费，请续卡密"),
                    resp.optString("pay_url").ifBlank { payUrl(key) },
                )
                else -> ActivateResult.Fail(resp.optString("err", "激活失败"))
            }
        }

    suspend fun verifyKey(context: Context, rawKey: String): VerifyResult = withContext(Dispatchers.IO) {
        val key = normalize(rawKey)
        if (key.isBlank()) return@withContext VerifyResult.Invalid
        val body = JSONObject().apply {
            put("key", key)
            put("device", deviceCode(context))
        }
        val resp = post("/verify", body) ?: return@withContext VerifyResult.NetworkError
        when {
            resp.optBoolean("ok") -> VerifyResult.Valid
            needsPay(resp) -> VerifyResult.NeedPay(
                resp.optString("err", "卡密已欠费，请续卡密"),
                resp.optString("pay_url").ifBlank { payUrl(key) },
            )
            else -> VerifyResult.Invalid
        }
    }

    suspend fun verify(context: Context): VerifyResult =
        verifyKey(context, storedKey(context).orEmpty())
}

@Composable
private fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F8)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Color(0xFF2A2A2A))
            Spacer(modifier = Modifier.height(16.dp))
            Text("正在校验授权…", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF8A8A8F))
        }
    }
}

@Composable
private fun ActivationScreen(
    initialKey: String,
    initialPayUrl: String?,
    onActivate: suspend (String, Boolean) -> ActivateResult,
    onPoll: suspend (String) -> VerifyResult,
    onDone: () -> Unit,
) {
    var input by remember { mutableStateOf(initialKey) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rememberKey by remember { mutableStateOf(true) }
    var payUrl by remember { mutableStateOf(initialPayUrl) }
    var showPay by remember { mutableStateOf(!initialPayUrl.isNullOrBlank()) }

    LaunchedEffect(initialPayUrl) {
        if (!initialPayUrl.isNullOrBlank()) {
            payUrl = initialPayUrl
            showPay = true
        }
    }

    val ink = Color(0xFF1F1F1F)
    val grey = Color(0xFF8A8A8F)

    fun submit() {
        busy = true
        error = null
        MainScope().launch {
            when (val r = onActivate(input, rememberKey)) {
                is ActivateResult.Ok -> {
                    busy = false
                    onDone()
                }
                is ActivateResult.NeedPay -> {
                    busy = false
                    error = r.msg
                    payUrl = r.url
                    showPay = true
                }
                is ActivateResult.Fail -> {
                    busy = false
                    error = r.msg
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F8)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 圆形头像（应用图标）
            Image(
                painter = painterResource(id = R.drawable.app_icon_photo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "chen",
                fontSize = 34.sp,
                fontWeight = FontWeight.Medium,
                color = ink,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "请输入卡密以继续使用",
                fontSize = 13.sp,
                color = grey,
            )

            Spacer(modifier = Modifier.height(34.dp))

            // 白色卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White)
                    .padding(22.dp),
            ) {
                Text(text = "卡密", fontSize = 12.sp, color = grey)

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    singleLine = true,
                    placeholder = { Text("输入卡密", color = Color(0xFFBDBDC4), fontSize = 14.sp) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error.orEmpty(),
                        color = Color(0xFFD33A3A),
                        fontSize = 12.sp,
                    )
                }

                if (!payUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = { showPay = true },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("打开收款码", fontSize = 12.5.sp, color = Color(0xFF5B4BD6))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "记住此卡密", fontSize = 13.sp, color = grey)
                    Switch(
                        checked = rememberKey,
                        onCheckedChange = { rememberKey = it },
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    enabled = !busy,
                    onClick = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ink,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = if (busy) "验证中…" else "验证并进入",
                        fontSize = 15.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            Text(
                text = "联网验证",
                fontSize = 11.sp,
                color = Color(0xFFAFAFB6),
                textAlign = TextAlign.Center,
            )
        }
    }

    val target = payUrl
    if (showPay && !target.isNullOrBlank()) {
        PayDialog(
            url = target,
            key = License.normalize(input),
            onPoll = onPoll,
            onPaid = {
                showPay = false
                submit()
            },
            onClose = { showPay = false },
        )
    }
}

/**
 * 欠费收款弹窗：内嵌服务端支付页（二维码），后台每 3 秒查一次支付状态，
 * 到账后自动关闭并重新验证，卡密恢复使用。
 */
@Composable
private fun PayDialog(
    url: String,
    key: String,
    onPoll: suspend (String) -> VerifyResult,
    onPaid: () -> Unit,
    onClose: () -> Unit,
) {
    var status by remember { mutableStateOf("扫码支付，到账后自动生效") }
    var ok by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(600.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "卡密续费",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F1F1F),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) {
                        Text("关闭", fontSize = 13.sp, color = Color(0xFF8A8A8F))
                    }
                }

                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = if (ok) Color(0xFF1A7F4B) else Color(0xFF8A8A8F),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )

                Spacer(modifier = Modifier.height(6.dp))

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webViewClient = WebViewClient()
                            loadUrl(url)
                        }
                    },
                )
            }
        }
    }

    LaunchedEffect(url) {
        var tries = 0
        while (tries < 200) {
            delay(3000)
            tries++
            if (onPoll(key) is VerifyResult.Valid) {
                ok = true
                status = "支付成功，卡密已恢复使用"
                delay(800)
                onPaid()
                return@LaunchedEffect
            }
        }
    }
}
