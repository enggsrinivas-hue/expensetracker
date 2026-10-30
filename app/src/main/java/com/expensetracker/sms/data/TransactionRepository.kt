package com.expensetracker.sms.data

import android.content.Context
import android.provider.Telephony
import com.expensetracker.sms.parser.SmsParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val dao: TransactionDao,
    @ApplicationContext private val context: Context
) {
    fun transactions(type: TransactionType, from: Long, to: Long, card: String?, query: String): Flow<List<ExpenseTransaction>> =
        dao.observe(type, from, to, card, query.trim())

    fun total(type: TransactionType, from: Long, to: Long, card: String?): Flow<Double> =
        dao.total(type, from, to, card)

    fun cards(): Flow<List<String>> = dao.cards()

    /** Parses one SMS and stores it. Returns true only if a NEW transaction was saved. */
    suspend fun ingest(sender: String, body: String, timestamp: Long): Boolean {
        val parsed = SmsParser.parse(sender, body) ?: return false
        val tx = ExpenseTransaction(
            timestamp = timestamp,
            transactionType = parsed.type,
            amount = parsed.amount,
            cardOrAccount = parsed.cardOrAccount,
            instrumentType = parsed.instrument,
            merchantOrSender = parsed.merchant,
            rawSms = body,
            smsHash = hash(sender, body, timestamp)
        )
        return dao.insert(tx) != -1L
    }

    /** One-time scan of the existing SMS inbox. Needs READ_SMS. Returns how many were added. */
    suspend fun syncInbox(): Int = withContext(Dispatchers.IO) {
        var added = 0
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC"
        ) ?: return@withContext 0
        cursor.use { c ->
            val a = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val b = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val d = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                if (ingest(c.getString(a) ?: "", c.getString(b) ?: "", c.getLong(d))) added++
            }
        }
        added
    }

    private fun hash(sender: String, body: String, timestamp: Long): String {
        val day = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val input = "${sender.lowercase()}|${body.trim()}|$day"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
