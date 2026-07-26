package com.omarabdelaziz.messagesapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class SendMessageWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val from = inputData.getString("from") ?: return Result.failure()
            val toRaw = inputData.getString("to") ?: return Result.failure()
            val text = inputData.getString("text") ?: return Result.failure()

            val db = FirebaseFirestore.getInstance()
            val recipients = toRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }

            recipients.forEach { to ->
                db.collection("messages").add(
                    mapOf(
                        "from" to from,
                        "to" to to,
                        "text" to text,
                        "timestamp" to System.currentTimeMillis(),
                        "participants" to listOf(from, to)
                    )
                ).await()
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
