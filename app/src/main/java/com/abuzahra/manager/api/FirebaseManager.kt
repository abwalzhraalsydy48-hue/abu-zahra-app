package com.abuzahra.manager.api

import android.util.Log
import com.abuzahra.manager.Config
import com.abuzahra.manager.model.Command
import com.google.firebase.database.*
import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object FirebaseManager {

    private val gson = Gson()
    private const val TAG = "FirebaseManager"

    private fun getRef(path: String): DatabaseReference {
        return FirebaseDatabase.getInstance()
            .getReferenceFromUrl(Config.FIREBASE_RTDB_URL)
            .child(path)
    }

    // ===== LISTEN FOR COMMANDS =====
    fun listenForCommands(deviceId: String): Flow<Command> = callbackFlow {
        val ref = getRef("commands/$deviceId")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val data = snapshot.value as? Map<*, *> ?: return
                    val json = gson.toJson(data)
                    val cmd = gson.fromJson(json, Command::class.java)
                    cmd.let { trySend(it) }
                    // Remove command after reading
                    snapshot.ref.removeValue()
                } catch (e: Exception) {
                    Log.e(TAG, "onChildAdded error", e)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Commands listener cancelled: ${error.message}")
            }
        }
        ref.addChildEventListener(listener)

        awaitClose {
            ref.removeEventListener(listener)
        }
    }

    // ===== SUBMIT RESULT =====
    fun submitResult(deviceId: String, cmdId: String, command: String, status: String, result: Any?) {
        try {
            val resultData = mapOf(
                "result" to (result?.let { if (it is String) it else gson.toJson(it) } ?: "OK"),
                "command" to command,
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )
            getRef("results/$deviceId/$cmdId").setValue(resultData)
            // Auto-delete after 30 seconds using a background thread
            Thread {
                try {
                    Thread.sleep(30000)
                    getRef("results/$deviceId/$cmdId").removeValue()
                } catch (_: InterruptedException) {}
            }.start()
            Log.d(TAG, "Firebase result submitted: $cmdId")
        } catch (e: Exception) {
            Log.e(TAG, "submitResult error", e)
        }
    }

    // ===== CHECK LINK CODE =====
    fun checkLinkCode(code: String, callback: (Boolean, String) -> Unit) {
        getRef("link_codes/$code").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.value as? Map<*, *>
                if (data == null) {
                    callback(false, "Invalid code")
                    return
                }
                val used = data["used"] as? Boolean ?: false
                if (used) {
                    callback(false, "Code already used")
                } else {
                    callback(true, "Code valid")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                callback(false, error.message)
            }
        })
    }
}
