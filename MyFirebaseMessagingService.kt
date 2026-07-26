package com.omarabdelaziz.messagesapp

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "رسالة جديدة"
        val body = message.notification?.body ?: "وصلتك رسالة جديدة"
        NotificationHelper.show(this, title, body)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // لو المستخدم داخل التطبيق حالياً، MainViewModel هيحفظ التوكن أيضاً
    }
}
