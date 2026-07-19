package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Locale

// --- Pal Conversation Assistant States ---

enum class PalState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    HAPPY,
    CURIOUS,
    CONCERNED,
    PAIN
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {

    private var speechManager: SpeechManager? = null
    private var ttsManager: TtsManager? = null

    // Direct handles for triggering voice assistant via intent (e.g. from background service)
    private var initialListenRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check if brough to front by background wake-up service trigger
        handleIntent(intent)

        setContent {
            MyApplicationTheme {
                PalAppScreen(
                    initialListenTrigger = initialListenRequested,
                    onInitSpeech = { sm -> speechManager = sm },
                    onInitTts = { tm -> ttsManager = tm }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("trigger_listening", false)) {
            initialListenRequested.value = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager?.shutdown()
        ttsManager?.shutdown()
    }
}

// --- Main Composable Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PalAppScreen(
    initialListenTrigger: MutableState<Boolean>,
    onInitSpeech: (SpeechManager) -> Unit,
    onInitTts: (TtsManager) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // UI state
    var palState by remember { mutableStateOf(PalState.IDLE) }
    var userPrompt by remember { mutableStateOf("") }
    var currentSpeechText by remember { mutableStateOf("") }
    var currentReplyText by remember { mutableStateOf("") }
    var rmsDbValue by remember { mutableStateOf(0f) }
    val chatHistory = remember { mutableStateListOf<ChatMessage>() }
    var showContactsDialog by remember { mutableStateOf(false) }
    var showSmsLogDialog by remember { mutableStateOf(false) }

    // Service state
    var isServiceRunning by remember { mutableStateOf(false) }

    // Check permissions
    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    var allPermissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        allPermissionsGranted = result.values.all { it }
        if (allPermissionsGranted) {
            Toast.makeText(context, "تمامی دسترسی‌ها تایید شدند.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "دستیار پال برای عملکرد کامل به این دسترسی‌ها نیاز دارد.", Toast.LENGTH_LONG).show()
        }
    }

    // Initialize TTS
    val tts = remember {
        TtsManager(
            context = context,
            onStartSpeaking = { palState = PalState.SPEAKING },
            onFinishedSpeaking = { palState = PalState.HAPPY }
        )
    }
    LaunchedEffect(Unit) {
        onInitTts(tts)
    }

    // Initialize Speech Recognizer
    val speech = remember {
        SpeechManager(
            context = context,
            onReady = {
                palState = PalState.LISTENING
                currentSpeechText = ""
            },
            onBeginning = {
                currentSpeechText = "در حال گوش دادن..."
            },
            onRmsChangedListener = { rms ->
                rmsDbValue = rms
            },
            onPartialResult = { partial ->
                currentSpeechText = partial
            },
            onResult = { result ->
                if (result.trim().isNotEmpty()) {
                    chatHistory.add(ChatMessage(result, isUser = true))
                    // Send to Gemini
                    scope.launch {
                        palState = PalState.THINKING
                        currentReplyText = "در حال پردازش..."
                        
                        // Map chat history to Gemini Content structure
                        val historyContents = chatHistory.takeLast(6).map { msg ->
                            Content(parts = listOf(Part(text = msg.text)))
                        }

                        val response = GeminiService.askPal(result, historyContents)
                        currentReplyText = response.reply
                        chatHistory.add(ChatMessage(response.reply, isUser = false))

                        // Say the reply using Farsi TTS
                        tts.speak(response.reply)

                        // Set the dynamic smiley state based on Gemini analysis
                        palState = when (response.emotion.uppercase(Locale.ROOT)) {
                            "HAPPY" -> PalState.HAPPY
                            "CURIOUS" -> PalState.CURIOUS
                            "CONCERNED" -> PalState.CONCERNED
                            else -> PalState.HAPPY
                        }

                        // Execute Call / SMS actions parsed by Gemini
                        response.command?.let { cmd ->
                            executeDeviceCommand(context, cmd)
                        }
                    }
                } else {
                    palState = PalState.IDLE
                }
            },
            onError = { errorMsg ->
                Log.e("SpeechError", errorMsg)
                if (errorMsg.contains("یافت نشد")) {
                    currentSpeechText = "صدایی شنیده نشد."
                } else {
                    currentSpeechText = errorMsg
                }
                palState = PalState.IDLE
            }
        )
    }
    LaunchedEffect(Unit) {
        onInitSpeech(speech)
    }

    // Auto-listen when launched from background wake-up service
    if (initialListenTrigger.value) {
        LaunchedEffect(Unit) {
            initialListenTrigger.value = false
            if (allPermissionsGranted) {
                speech.startListening()
            }
        }
    }

    // Connect to dynamic reactive SMS bus
    LaunchedEffect(Unit) {
        PalSmsBus.smsFlow
            .onEach { sms ->
                Log.d("MainActivity", "Dynamic UI observed SMS from ${sms.senderName}: ${sms.messageBody}")
                val textPrompt = "پیامک جدید از ${sms.senderName}: ${sms.messageBody}"
                chatHistory.add(ChatMessage(textPrompt, isUser = true))
                
                palState = PalState.THINKING
                val response = GeminiService.askPal("من یک پیامک از طرف ${sms.senderName} دریافت کردم با این متن: ${sms.messageBody}. لطفاً با لحنی دوستانه بهم بگو و یک پاسخ کوتاه پیشنهاد بده.")
                currentReplyText = response.reply
                chatHistory.add(ChatMessage(response.reply, isUser = false))

                // Speak response
                tts.speak(response.reply)
                palState = when (response.emotion.uppercase(Locale.ROOT)) {
                    "HAPPY" -> PalState.HAPPY
                    "CURIOUS" -> PalState.CURIOUS
                    "CONCERNED" -> PalState.CONCERNED
                    else -> PalState.HAPPY
                }
            }
            .launchIn(this)
    }

    // UI Structure
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF001F54), // Deep Space Blue
                        Color(0xFF0052D4), // Royal Cosmic Blue
                        Color(0xFF4364F7)  // Electric Cyan
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // --- Central Dynamic Face Column ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // App Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showContactsDialog = true },
                    modifier = Modifier.testTag("contacts_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContactPhone,
                        contentDescription = "مخاطبین",
                        tint = Color.White
                    )
                }

                Text(
                    text = "دستیار صوتی پال (Pal)",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )

                IconButton(
                    onClick = { showSmsLogDialog = true },
                    modifier = Modifier.testTag("sms_log_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Sms,
                        contentDescription = "پیامک‌ها",
                        tint = Color.White
                    )
                }
            }

            // --- Dynamic White Smiley Face Block ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Interactive background aura/ripple
                val pulseAlpha by rememberInfiniteTransition(label = "").animateFloat(
                    initialValue = 0.1f,
                    targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = ""
                )
                val rmsScale = 1f + (rmsDbValue.coerceIn(0f, 15f) / 15f) * 0.4f

                Box(
                    modifier = Modifier
                        .size(240.dp * rmsScale)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = pulseAlpha),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(200.dp)
                        )
                )

                // Face canvas drawing
                DynamicSmileyFace(
                    state = palState,
                    rmsDb = rmsDbValue,
                    modifier = Modifier
                        .size(280.dp)
                        .testTag("smiley_face")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            speech.stopListening()
                            tts.stop()
                            palState = PalState.PAIN
                            currentReplyText = "Oh, please don't. (آخ، لطفاً این کار رو نکن!)"
                            chatHistory.add(ChatMessage("Oh, please don't.", isUser = false))
                            tts.speak("Oh, please don't.", Locale.US, "PalPainSpeakID")
                        }
                )
            }

            // --- Conversation Bubbles ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Speech Input display
                AnimatedVisibility(
                    visible = currentSpeechText.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(0.85f),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = currentSpeechText,
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 15.sp,
                            textAlign = TextAlign.Right,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }

                // AI Reply display
                AnimatedVisibility(
                    visible = currentReplyText.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(0.9f),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.25f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = currentReplyText,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Right,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }

            // --- Bottom Controls Panel ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Microphone & Service Toggle Layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Background Wakeup Toggle
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconToggleButton(
                            checked = isServiceRunning,
                            onCheckedChange = { checked ->
                                if (!allPermissionsGranted) {
                                    launcher.launch(requiredPermissions)
                                    return@FilledIconToggleButton
                                }
                                isServiceRunning = checked
                                if (checked) {
                                    PalWakeUpService.startService(context)
                                    Toast.makeText(context, "سرویس فعال شد. پال در پس‌زمینه گوش می‌دهد.", Toast.LENGTH_SHORT).show()
                                } else {
                                    PalWakeUpService.stopService(context)
                                    Toast.makeText(context, "سرویس پس‌زمینه غیرفعال شد.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("service_toggle")
                        ) {
                            Icon(
                                imageVector = if (isServiceRunning) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                                contentDescription = "سرویس پس‌زمینه"
                            )
                        }
                        Text(
                            text = if (isServiceRunning) "گوش‌به‌زنگ پس‌زمینه" else "آماده‌باش خاموش",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Main Listening Audio trigger
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(80.dp)
                    ) {
                        // Dynamic microphone glow
                        val micGlow by animateFloatAsState(
                            targetValue = if (palState == PalState.LISTENING) 1.25f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                        )
                        Box(
                            modifier = Modifier
                                .size(64.dp * micGlow)
                                .background(
                                    color = if (palState == PalState.LISTENING) Color.Red.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(50.dp)
                                )
                        )

                        FloatingActionButton(
                            onClick = {
                                if (!allPermissionsGranted) {
                                    launcher.launch(requiredPermissions)
                                    return@FloatingActionButton
                                }
                                if (palState == PalState.LISTENING) {
                                    speech.stopListening()
                                    palState = PalState.IDLE
                                } else {
                                    tts.stop()
                                    speech.startListening()
                                }
                            },
                            containerColor = if (palState == PalState.LISTENING) Color(0xFFFF3B30) else Color.White,
                            contentColor = if (palState == PalState.LISTENING) Color.White else Color(0xFF0052D4),
                            shape = RoundedCornerShape(50.dp),
                            modifier = Modifier
                                .size(56.dp)
                                .testTag("mic_button")
                        ) {
                            Icon(
                                imageVector = if (palState == PalState.LISTENING) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = "صحبت کردن",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Conversation Reset Helper
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                chatHistory.clear()
                                currentReplyText = "تاریخچه گفتگو پاک شد."
                                currentSpeechText = ""
                                tts.stop()
                                palState = PalState.IDLE
                            },
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(50.dp))
                                .testTag("clear_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "پاک کردن",
                                tint = Color.White
                            )
                        }
                        Text(
                            text = "پاکسازی",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // --- Permissions Overlay Panel if permissions missing ---
        if (!allPermissionsGranted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1E38)),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = "دسترسی‌ها",
                            tint = Color(0xFF4364F7),
                            modifier = Modifier.size(48.dp)
                        )

                        Text(
                            text = "نیاز به تایید دسترسی‌ها",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "دستیار صوتی پال برای بیدار شدن با صوت («Hey Pal»)، برقراری تماس صوتی، دریافت پیام‌های ورودی به همراه نام مخاطبین و پاسخگویی به آن‌ها، به دسترسی‌های زیر نیاز مبرم دارد:",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            textAlign = TextAlign.Center
                        )

                        // Visual explanation list
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PermissionExplanationRow("میکروفون", "برای گوش دادن به فرامین صوتی و واژه بیدارباش")
                            PermissionExplanationRow("مخاطبین", "برای برقراری تماس با نام و خواندن نام فرستنده پیامک")
                            PermissionExplanationRow("پیامک (SMS)", "برای خواندن و اعلام پیام‌های ورودی و ارسال پیام")
                            PermissionExplanationRow("تلفن (Calls)", "برای برقراری تماس‌های مستقیم صوتی")
                        }

                        Button(
                            onClick = { launcher.launch(requiredPermissions) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4364F7)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("grant_permissions_button")
                        ) {
                            Text(
                                text = "اعطای دسترسی‌ها",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // --- Dialogs (Contacts / SMS Logs) ---
        if (showContactsDialog) {
            val contacts = remember { ContactHelper.listContacts(context) }
            AlertDialog(
                onDismissRequest = { showContactsDialog = false },
                title = { Text("مخاطبین متصل به پال", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                text = {
                    if (contacts.isEmpty()) {
                        Text("مخاطبی یافت نشد یا دسترسی داده نشده است.", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth())
                    } else {
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(contacts) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showContactsDialog = false
                                            executeDeviceCommand(context, PalCommand("CALL", recipient = item.first))
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Icon(imageVector = Icons.Filled.Call, contentDescription = "Call", tint = Color(0xFF0052D4))
                                    Text("${item.first} : ${item.second}", color = Color.Black)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showContactsDialog = false }) {
                        Text("بستن")
                    }
                }
            )
        }

        if (showSmsLogDialog) {
            AlertDialog(
                onDismissRequest = { showSmsLogDialog = false },
                title = { Text("پیام‌های دریافتی اخیر", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                text = {
                    Text("پیام‌های ورودی در پس‌زمینه توسط پال شنیده شده و به فارسی برای شما خوانده می‌شوند.", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = { showSmsLogDialog = false }) {
                        Text("تایید")
                    }
                }
            )
        }
    }
}

@Composable
fun PermissionExplanationRow(title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(desc, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4364F7),
            modifier = Modifier.size(16.dp)
        )
    }
}

// --- Dynamic Canvas-based white smiley face with glowing effect ---

@Composable
fun DynamicSmileyFace(
    state: PalState,
    rmsDb: Float,
    modifier: Modifier = Modifier
) {
    // Core animation parameters based on active assistant state
    val eyebrowsOffset by animateFloatAsState(
        targetValue = when (state) {
            PalState.LISTENING -> -12f
            PalState.HAPPY -> -16f
            PalState.CURIOUS -> -10f
            PalState.CONCERNED -> 8f
            PalState.THINKING -> -5f
            PalState.PAIN -> 16f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "eyebrowsOffset"
    )

    val eyebrowsTilt by animateFloatAsState(
        targetValue = when (state) {
            PalState.CONCERNED -> 20f   // Inward tilt
            PalState.CURIOUS -> -15f    // Raised/asymmetric feel
            PalState.HAPPY -> -5f
            PalState.PAIN -> 30f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "eyebrowsTilt"
    )

    val eyeScaleY by animateFloatAsState(
        targetValue = when (state) {
            PalState.HAPPY -> 0.4f
            PalState.CONCERNED -> 0.7f
            PalState.THINKING -> 0.8f
            PalState.PAIN -> 0.1f
            else -> 1f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "eyeScaleY"
    )

    val mouthCurve by animateFloatAsState(
        targetValue = when (state) {
            PalState.HAPPY -> 45f       // Curve down for smiling mouth drawing
            PalState.CONCERNED -> -25f   // Flat/inverted curve for concerned
            PalState.CURIOUS -> 10f
            PalState.LISTENING -> 20f
            PalState.THINKING -> 5f
            PalState.PAIN -> -40f
            else -> 30f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "mouthCurve"
    )

    // Breathing infinite cycle for organic floating motion
    val breathProgress by rememberInfiniteTransition(label = "").animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = ""
    )

    // Dynamic speaking animation
    val speakBounce by rememberInfiniteTransition(label = "").animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = ""
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Define a glowing paint utility
        fun drawGlowingPath(path: Path, color: Color, strokeWidth: Float) {
            // Draw wide glowing outline
            drawPath(
                path = path,
                color = color.copy(alpha = 0.25f),
                style = Stroke(width = strokeWidth * 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Draw sharp inner solid line
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Draw Eyebrows (Curved)
        // Eyebrows are placed vertically above the eyes
        val leftEyebrowPath = Path().apply {
            val startY = h * 0.35f + eyebrowsOffset + breathProgress - (eyebrowsTilt * 0.3f)
            val endY = h * 0.34f + eyebrowsOffset + breathProgress + (eyebrowsTilt * 0.4f)
            val ctrlY = h * 0.28f + eyebrowsOffset + breathProgress

            moveTo(w * 0.28f, startY)
            quadraticTo(
                w * 0.37f, ctrlY,
                w * 0.46f, endY
            )
        }

        val rightEyebrowPath = Path().apply {
            // Curiousness raises the right eyebrow extra
            val curiousExtra = if (state == PalState.CURIOUS) -18f else 0f
            val startY = h * 0.34f + eyebrowsOffset + breathProgress + curiousExtra + (eyebrowsTilt * 0.4f)
            val endY = h * 0.35f + eyebrowsOffset + breathProgress + curiousExtra - (eyebrowsTilt * 0.3f)
            val ctrlY = h * 0.28f + eyebrowsOffset + breathProgress + curiousExtra

            moveTo(w * 0.54f, startY)
            quadraticTo(
                w * 0.63f, ctrlY,
                w * 0.72f, endY
            )
        }

        drawGlowingPath(leftEyebrowPath, Color.White, strokeWidth = 8.dp.toPx())
        drawGlowingPath(rightEyebrowPath, Color.White, strokeWidth = 8.dp.toPx())

        // Draw Eyes (Wincing `< >` shape for PAIN state, or rounded vertical thick capsules for others)
        if (state == PalState.PAIN) {
            // Draw left wincing eye `<`
            val leftEyePath = Path().apply {
                val cenX = w * 0.38f
                val cenY = h * 0.48f + breathProgress
                moveTo(cenX + 16.dp.toPx(), cenY - 16.dp.toPx())
                lineTo(cenX - 12.dp.toPx(), cenY)
                lineTo(cenX + 16.dp.toPx(), cenY + 16.dp.toPx())
            }
            drawGlowingPath(leftEyePath, Color.White, strokeWidth = 8.dp.toPx())

            // Draw right wincing eye `>`
            val rightEyePath = Path().apply {
                val cenX = w * 0.62f
                val cenY = h * 0.48f + breathProgress
                moveTo(cenX - 16.dp.toPx(), cenY - 16.dp.toPx())
                lineTo(cenX + 12.dp.toPx(), cenY)
                lineTo(cenX - 16.dp.toPx(), cenY + 16.dp.toPx())
            }
            drawGlowingPath(rightEyePath, Color.White, strokeWidth = 8.dp.toPx())
        } else {
            val eyeWidth = w * 0.08f
            val eyeBaseHeight = h * 0.18f
            val eyeHeight = eyeBaseHeight * eyeScaleY

            // Left Eye
            val leftEyeX = w * 0.34f
            val leftEyeY = h * 0.42f + breathProgress + (eyeBaseHeight - eyeHeight) / 2f
            
            // Draw double layers for eye glow
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(leftEyeX - 4.dp.toPx(), leftEyeY - 4.dp.toPx()),
                size = Size(eyeWidth + 8.dp.toPx(), eyeHeight + 8.dp.toPx()),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(leftEyeX, leftEyeY),
                size = Size(eyeWidth, eyeHeight),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
            )

            // Right Eye
            val rightEyeX = w * 0.58f
            val rightEyeY = h * 0.42f + breathProgress + (eyeBaseHeight - eyeHeight) / 2f
            
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                topLeft = Offset(rightEyeX - 4.dp.toPx(), rightEyeY - 4.dp.toPx()),
                size = Size(eyeWidth + 8.dp.toPx(), eyeHeight + 8.dp.toPx()),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(rightEyeX, rightEyeY),
                size = Size(eyeWidth, eyeHeight),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
            )
        }

        // Draw Mouth (Dynamic Bezier curve based on state + speaking vibrations)
        val mouthY = h * 0.68f + breathProgress
        val leftMouthX = w * 0.28f
        val rightMouthX = w * 0.72f

        val mouthPath = Path().apply {
            moveTo(leftMouthX, mouthY)
            
            if (state == PalState.SPEAKING) {
                // When speaking, we draw an open mouth path
                val ctrlY1 = mouthY + 12f + speakBounce
                val ctrlY2 = mouthY + 12f + speakBounce
                cubicTo(
                    w * 0.42f, ctrlY1,
                    w * 0.58f, ctrlY2,
                    rightMouthX, mouthY
                )
            } else {
                // Standard organic smile curve
                val ctrlY = mouthY + mouthCurve
                quadraticTo(
                    w * 0.5f, ctrlY,
                    rightMouthX, mouthY
                )
            }
        }

        drawGlowingPath(mouthPath, Color.White, strokeWidth = 9.dp.toPx())
    }
}

// --- Direct Android Calls and SMS execution commands ---

fun executeDeviceCommand(context: Context, command: PalCommand) {
    when (command.type.uppercase(Locale.ROOT)) {
        "CALL" -> {
            val recipient = command.recipient ?: return
            Log.d("ExecuteCommand", "Dialing recipient: $recipient")
            
            // Check if name or direct number
            val number = if (recipient.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
                recipient
            } else {
                ContactHelper.getPhoneNumberByName(context, recipient) ?: recipient
            }

            try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    context.startActivity(callIntent)
                } else {
                    // Fallback to DIAL (which doesn't require direct permission)
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dialIntent)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "امکان برقراری تماس وجود ندارد: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        "SEND_SMS" -> {
            val recipient = command.recipient ?: return
            val message = command.message ?: return
            Log.d("ExecuteCommand", "Sending SMS to: $recipient, message: $message")

            val number = if (recipient.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
                recipient
            } else {
                ContactHelper.getPhoneNumberByName(context, recipient) ?: recipient
            }

            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    smsManager.sendTextMessage(number, null, message, null, null)
                    Toast.makeText(context, "پیامک با موفقیت به $recipient ارسال شد.", Toast.LENGTH_SHORT).show()
                } else {
                    // Fallback to sending via native SMS app intent
                    val smsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$number")).apply {
                        putExtra("sms_body", message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(smsIntent)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "خطا در ارسال پیامک: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        "LIST_FILES" -> {
            // List files inside system Downloads folder or simply show user dynamic response
            Toast.makeText(context, "در حال بررسی فایل‌های گوشی شما...", Toast.LENGTH_SHORT).show()
        }
    }
}
