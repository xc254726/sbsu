package org.bakasu.bakasu.ui

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

                            LaunchedEffect(Unit) {
                                gate = if (License.hasKey(ctx)) {
                                    val r = License.verify(ctx)
                                    if (r == VerifyResult.VALID || r == VerifyResult.NETWORK_ERROR) {
                                        GateState.OK
                                    } else {
                                        GateState.NEED_ACTIVATION
                                    }
                                } else {
                                    GateState.NEED_ACTIVATION
                                }
                            }

                            when (gate) {
                                GateState.OK -> NavContainer(settingsViewModel, intentChannel)
                                GateState.CHECKING -> LoadingScreen()
                                GateState.NEED_ACTIVATION -> ActivationScreen(
                                    onActivate = { input, remember ->
                                        License.activate(ctx, input, remember)
                                    },
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

enum class VerifyResult { VALID, INVALID, NETWORK_ERROR }

/**
 * 在线卡密校验
 *
 * 服务端：https://sbkey.dpdns.org (Cloudflare Worker + KV)
 *    POST /activate {key, device}
 *    POST /verify   {key, device}
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

    suspend fun activate(context: Context, input: String, remember: Boolean): String? =
        withContext(Dispatchers.IO) {
            val key = input.uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '-' }
            if (key.length < 8) return@withContext "卡密格式不对"

            val body = JSONObject().apply {
                put("key", key)
                put("device", deviceCode(context))
            }
            val resp = post("/activate", body) ?: return@withContext "网络连接失败，请检查网络"

            if (resp.optBoolean("ok")) {
                if (remember) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY, key).apply()
                }
                null
            } else {
                resp.optString("err", "激活失败")
            }
        }

    suspend fun verify(context: Context): VerifyResult = withContext(Dispatchers.IO) {
        val key = storedKey(context) ?: return@withContext VerifyResult.INVALID
        val body = JSONObject().apply {
            put("key", key)
            put("device", deviceCode(context))
        }
        val resp = post("/verify", body) ?: return@withContext VerifyResult.NETWORK_ERROR
        if (resp.optBoolean("ok")) VerifyResult.VALID else VerifyResult.INVALID
    }

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
    onActivate: suspend (String, Boolean) -> String?,
    onDone: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rememberKey by remember { mutableStateOf(true) }

    val ink = Color(0xFF1F1F1F)
    val grey = Color(0xFF8A8A8F)

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
                text = "sb",
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
                    onClick = {
                        busy = true
                        error = null
                        MainScope().launch {
                            val err = onActivate(input, rememberKey)
                            busy = false
                            if (err == null) onDone() else error = err
                        }
                    },
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
}
