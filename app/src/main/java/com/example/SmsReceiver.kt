package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class SmsMessage(
    val senderNumber: String,
    val senderName: String,
    val messageBody: String
)

object PalSmsBus {
    private val _smsFlow = MutableSharedFlow<SmsMessage>(extraBufferCapacity = 10)
    val smsFlow = _smsFlow.asSharedFlow()

    fun postSms(message: SmsMessage) {
        _smsFlow.tryEmit(message)
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (msg in messages) {
                val senderNum = msg.originatingAddress ?: "Unknown"
                val body = msg.messageBody ?: ""

                // Lookup name from contact
                val senderName = ContactHelper.getPhoneNumberByName(context, senderNum) ?: senderNum
                Log.d("SmsReceiver", "SMS Received from: $senderName ($senderNum): $body")

                val smsMsg = SmsMessage(
                    senderNumber = senderNum,
                    senderName = senderName,
                    messageBody = body
                )
                
                // Emit to the shared reactive bus
                PalSmsBus.postSms(smsMsg)
            }
        }
    }
}
