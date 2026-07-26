package com.omarabdelaziz.messagesapp

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.compose.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val CHANNEL_ID = "messages_channel"
private const val APP_TITLE = "MessagesApp"
private const val DEVELOP_URL = "https://omarabdelazizbe.vercel.app/"

class MessagesApp : Application()

data class ChatMessage(
    val id: String = "",
    val from: String = "",
    val to: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val participants: List<String> = emptyList()
)

data class BottomItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        NotificationHelper.createChannel(this)

        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val _phone = MutableStateFlow(auth.currentUser?.phoneNumber.orEmpty())
    val phone: StateFlow<String> = _phone.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var registration: com.google.firebase.firestore.ListenerRegistration? = null

    fun refreshPhone() {
        _phone.value = auth.currentUser?.phoneNumber.orEmpty()
    }

    fun observeMessages() {
        val p = auth.currentUser?.phoneNumber ?: return
        registration?.remove()
        registration = db.collection("messages")
            .whereArrayContains("participants", p)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { d ->
                    ChatMessage(
                        id = d.id,
                        from = d.getString("from").orEmpty(),
                        to = d.getString("to").orEmpty(),
                        text = d.getString("text").orEmpty(),
                        timestamp = d.getLong("timestamp") ?: 0L,
                        participants = d.get("participants") as? List<String> ?: emptyList()
                    )
                }.orEmpty()
                _messages.value = list
            }
    }

    fun stopObserving() {
        registration?.remove()
        registration = null
    }

    fun saveCurrentUserToken(token: String) {
        val p = auth.currentUser?.phoneNumber ?: return
        db.collection("users").document(p)
            .set(
                mapOf(
                    "phone" to p,
                    "fcmToken" to token,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
    }

    fun createOrUpdateProfile() {
        val p = auth.currentUser?.phoneNumber ?: return
        db.collection("users").document(p)
            .set(
                mapOf(
                    "phone" to p,
                    "name" to "User",
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
    }

    fun sendMessageNow(from: String, rawRecipients: String, text: String) {
        val recipients = rawRecipients.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        recipients.forEach { to ->
            val message = hashMapOf(
                "from" to from,
                "to" to to,
                "text" to text,
                "timestamp" to System.currentTimeMillis(),
                "participants" to listOf(from, to)
            )
            db.collection("messages").add(message)
        }
    }

    fun scheduleMessage(
        from: String,
        rawRecipients: String,
        text: String,
        delayMinutes: Long
    ) {
        val data = workDataOf(
            "from" to from,
            "to" to rawRecipients,
            "text" to text
        )

        val request = OneTimeWorkRequestBuilder<SendMessageWorker>()
            .setInputData(data)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(getApplication())
            .enqueue(request)
    }

    fun signOut() {
        auth.signOut()
        stopObserving()
        _phone.value = ""
    }
}

class MainViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(app) as T
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val activity = context.findActivity()
    val vm: MainViewModel = viewModel(factory = MainViewModelFactory(context.applicationContext as Application))
    val auth = remember { FirebaseAuth.getInstance() }
    val nav = rememberNavController()
    val signedIn = auth.currentUser != null

    val notifPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(auth.currentUser?.uid) {
        vm.refreshPhone()
        if (auth.currentUser != null) {
            vm.createOrUpdateProfile()
            vm.observeMessages()
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                vm.saveCurrentUserToken(token)
            }
        }
    }

    if (!signedIn) {
        PhoneAuthScreen(
            onSignedIn = {
                vm.refreshPhone()
                nav.navigate("messages") {
                    popUpTo(0)
                }
            }
        )
    } else {
        AppScaffold(
            vm = vm,
            nav = nav,
            onLogout = {
                vm.signOut()
                nav.navigate("login") {
                    popUpTo(0)
                }
            }
        )
    }
}

@Composable
fun AppScaffold(
    vm: MainViewModel,
    nav: NavHostController,
    onLogout: () -> Unit
) {
    val phone by vm.phone.collectAsState()
    val messages by vm.messages.collectAsState()

    val items = listOf(
        BottomItem("messages", "الرسائل", Icons.Default.Home),
        BottomItem("info", "المعلومات", Icons.Default.Info),
        BottomItem("settings", "الإعدادات", Icons.Default.Settings)
    )

    val bottomBarRoutes = setOf("messages", "info", "settings")

    Scaffold(
        floatingActionButton = {
            val route = nav.currentBackStackEntryAsState().value?.destination?.route
            if (route == "messages") {
                FloatingActionButton(onClick = { nav.navigate("newchat") }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = nav.currentBackStackEntryAsState().value?.destination?.route == item.route,
                        onClick = {
                            nav.navigate(item.route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "messages",
            modifier = Modifier.padding(padding)
        ) {
            composable("messages") {
                MessagesScreen(
                    phone = phone,
                    messages = messages,
                    onOpenChat = { to ->
                        nav.navigate("chat/${Uri.encode(to)}")
                    },
                    onNewChat = { nav.navigate("newchat") }
                )
            }
            composable("info") {
                InfoScreen()
            }
            composable("settings") {
                SettingsScreen(
                    onOpenPage = { title, body ->
                        nav.navigate("static/${Uri.encode(title)}/${Uri.encode(body)}")
                    },
                    onOpenWeb = {
                        nav.navigate("web/${Uri.encode(DEVELOP_URL)}")
                    },
                    onLogout = onLogout
                )
            }
            composable(
                "chat/{to}",
                arguments = listOf(navArgument("to") { type = NavType.StringType })
            ) { entry ->
                val to = entry.arguments?.getString("to").orEmpty()
                ChatScreen(
                    myPhone = phone,
                    otherPhone = to,
                    vm = vm,
                    onBack = { nav.popBackStack() }
                )
            }
            composable("newchat") {
                NewChatScreen(
                    myPhone = phone,
                    onSendNow = { to, text ->
                        vm.sendMessageNow(phone, to, text)
                        nav.popBackStack()
                    },
                    onSchedule = { to, text, minutes ->
                        vm.scheduleMessage(phone, to, text, minutes)
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() }
                )
            }
            composable(
                "static/{title}/{body}",
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType },
                    navArgument("body") { type = NavType.StringType }
                )
            ) { entry ->
                StaticPageScreen(
                    title = entry.arguments?.getString("title").orEmpty(),
                    body = entry.arguments?.getString("body").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
            composable(
                "web/{url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType })
            ) { entry ->
                WebScreen(
                    url = entry.arguments?.getString("url").orEmpty(),
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun PhoneAuthScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val auth = remember { FirebaseAuth.getInstance() }

    var phone by remember { mutableStateOf("+20") }
    var code by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var codeSent by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val callbacks = remember {
        object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                auth.signInWithCredential(credential).addOnSuccessListener {
                    onSignedIn()
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                loading = false
                Toast.makeText(context, e.message ?: "Verification failed", Toast.LENGTH_LONG).show()
            }

            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                verificationId = id
                codeSent = true
                loading = false
                Toast.makeText(context, "تم إرسال الكود", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun sendOtp() {
        val act = activity ?: return
        loading = true
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone.trim())
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(act)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    fun verifyOtp() {
        val id = verificationId ?: return
        val credential = PhoneAuthProvider.getCredential(id, code.trim())
        loading = true
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                loading = false
                onSignedIn()
            }
            .addOnFailureListener { e ->
                loading = false
                Toast.makeText(context, e.message ?: "Wrong code", Toast.LENGTH_LONG).show()
            }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("تسجيل الدخول", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("رقم الهاتف") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { sendOtp() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("إرسال كود التحقق")
            }

            Spacer(Modifier.height(18.dp))

            if (codeSent) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("كود OTP") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { verifyOtp() },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تأكيد الرقم")
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("اكتب الرقم بصيغة دولية، مثال: +201234567890")
        }
    }
}

@Composable
fun MessagesScreen(
    phone: String,
    messages: List<ChatMessage>,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit
) {
    val now = System.currentTimeMillis()
    val recent = messages.filter { now - it.timestamp < 24 * 60 * 60 * 1000 }
    val old = messages.filter { now - it.timestamp >= 24 * 60 * 60 * 1000 }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("الرسائل", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        Text("الرسائل الجديدة", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(recent) { msg ->
                MessageCard(phone, msg, onOpenChat)
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text("الرسائل القديمة", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            items(old) { msg ->
                MessageCard(phone, msg, onOpenChat)
            }
        }
    }
}

@Composable
fun MessageCard(myPhone: String, message: ChatMessage, onOpenChat: (String) -> Unit) {
    val other = if (message.from == myPhone) message.to else message.from
    Card(
        onClick = { onOpenChat(other) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("مع: $other", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(message.text)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (message.from == myPhone) "أنت" else "وارد",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
fun ChatScreen(
    myPhone: String,
    otherPhone: String,
    vm: MainViewModel,
    onBack: () -> Unit
) {
    val messages by vm.messages.collectAsState()
    val thread = messages.filter {
        (it.from == myPhone && it.to == otherPhone) || (it.from == otherPhone && it.to == myPhone)
    }.sortedByDescending { it.timestamp }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("محادثة: $otherPhone", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), reverseLayo
