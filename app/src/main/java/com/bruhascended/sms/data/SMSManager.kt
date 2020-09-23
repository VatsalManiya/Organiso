/*
                    Copyright 2020 Chirag Kalra

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package com.bruhascended.sms.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.bruhascended.sms.R
import com.bruhascended.sms.analytics.AnalyticsLogger
import com.bruhascended.sms.db.Conversation
import com.bruhascended.sms.db.ConversationDatabase
import com.bruhascended.sms.db.Message
import com.bruhascended.sms.db.MessageDatabase
import com.bruhascended.sms.ml.OrganizerModel
import com.bruhascended.sms.isMainViewModelNull
import com.bruhascended.sms.ui.main.MainViewModel
import com.bruhascended.sms.mainViewModel
import com.bruhascended.sms.ui.start.StartViewModel

val labelText = arrayOf(
    R.string.tab_text_1,
    R.string.tab_text_2,
    R.string.tab_text_3,
    R.string.tab_text_4,
    R.string.tab_text_5,
    R.string.tab_text_6
)

const val MESSAGE_CHECK_COUNT = 6

class SMSManager(
    private val mContext: Context,
    private val pageViewModel: StartViewModel
) {
    private val nn = OrganizerModel(mContext)
    private val cm = ContactsManager(mContext)
    private val mmsManager = MMSManager(mContext)
    private val sp = mContext.getSharedPreferences("local", Context.MODE_PRIVATE)

    private val messages = HashMap<String, ArrayList<Message>>()
    private val senderToProbs = HashMap<String, FloatArray>()
    private val senderForce = HashMap<String, Int>()

    private var done = 0
    private var index = 0
    private var total = 0

    private var timeTaken = 0L
    private var startTime = 0L

    private lateinit var senderNameMap: HashMap<String, String>

    private lateinit var mmsThread: Thread

    private fun finish() {
        pageViewModel.disc.postValue(2)

        nn.close()
        senderNameMap.clear()
        senderToProbs.clear()
        senderForce.clear()

        mContext.getSharedPreferences("local", Context.MODE_PRIVATE).edit()
            .putLong("last", System.currentTimeMillis())
            .putInt("index", 0)
            .putInt("done", 0)
            .apply()
    }

    private fun updateProgress(size: Int) {
        done += size
        timeTaken = (System.currentTimeMillis()-startTime)
        val per = done * 100f / total
        val eta = (System.currentTimeMillis()-startTime) * (100/per-1)
        pageViewModel.progress.postValue(per)
        pageViewModel.eta.postValue(eta.toLong())

        AnalyticsLogger(mContext).log("conversation_organised", "init")
    }

    private fun saveMessage(ind: Int, sender: String, messages: ArrayList<Message>, label: Int) {
        if (isMainViewModelNull()) {
            mainViewModel = MainViewModel()
            mainViewModel.daos = Array(6){
                Room.databaseBuilder(
                    mContext, ConversationDatabase::class.java,
                    mContext.resources.getString(labelText[it])
                ).allowMainThreadQueries().build().manager()
            }
        }

        var conversation: Conversation? = null
        for (i in 0..4) {
            val got = mainViewModel.daos[i].findBySender(sender)
            if (got.isNotEmpty()) {
                conversation = got.first()
                for (item in got.slice(1 until got.size))
                    mainViewModel.daos[i].delete(item)
                break
            }
        }

        if (conversation != null) {
            conversation.apply {
                read = false
                time = messages.last().time
                lastSMS =  messages.last().text
                lastMMS = false
                name = senderNameMap[sender]
                mainViewModel.daos[0].update(this)
            }
        } else {
            val con = Conversation(
                null,
                sender,
                senderNameMap[sender],
                "",
                true,
                messages.last().time,
                messages.last().text,
                label,
                senderForce[sender] ?: -1,
                senderToProbs[sender] ?: FloatArray(5) { if (it == 0) 1f else 0f }
            )
            mainViewModel.daos[label].insert(con)
        }


        Room.databaseBuilder(
            mContext, MessageDatabase::class.java, sender
        ).build().apply {
            manager().insertAll(messages.toList())
            close()
        }

        mContext.getSharedPreferences("local", Context.MODE_PRIVATE).edit()
            .putInt("index", ind + 1)
            .putInt("done", done)
            .putLong("timeTaken", timeTaken)
            .apply()
    }

    fun getMessages() {
        val lastDate = sp.getLong("last", 0).toString()

        mContext.contentResolver.query(
            Uri.parse("content://sms/"),
            null,
            "date" + ">?",
            arrayOf(lastDate),
            "date ASC"
        ) ?.apply {
            if (moveToFirst()) {
                val nameID = getColumnIndex("address")
                val messageID = getColumnIndex("body")
                val dateID = getColumnIndex("date")
                val typeID = getColumnIndex("type")

                do {
                    var name = getString(nameID)
                    val messageContent = getString(messageID)
                    if (name != null && !messageContent.isNullOrEmpty()) {
                        name = cm.getRaw(name)
                        val message = Message(
                            null,
                            name,
                            messageContent,
                            getString(typeID).toInt(),
                            getString(dateID).toLong(),
                            -1
                        )

                        if (messages.containsKey(name)) messages[name]?.add(message)
                        else messages[name] = arrayListOf(message)
                    }
                } while (moveToNext())
            }
            close()
        }
        mmsThread = Thread { mmsManager.getMMS(lastDate) }
        mmsThread.start()
    }

    fun getLabels() {
        if (isMainViewModelNull()) {
            mainViewModel = MainViewModel()
            mainViewModel.daos = Array(6){
                Room.databaseBuilder(
                    mContext, ConversationDatabase::class.java,
                    mContext.resources.getString(labelText[it])
                ).allowMainThreadQueries().build().manager()
            }
        }

        done = sp.getInt("done", 0)
        index = sp.getInt("index", 0)
        senderNameMap = ContactsManager(mContext).getContactsHashMap()

        for ((_, msgs) in messages) total += msgs.size

        val messagesArray = messages.entries.toTypedArray()
        val number = messagesArray.size

        var saveThread: Thread? = null
        startTime = System.currentTimeMillis() - sp.getLong("timeTaken", 0)

        for (ind in index until number) {
            val sender = messagesArray[ind].component1()
            val msgs = messagesArray[ind].component2()
            var label: Int

            if (senderNameMap.containsKey(sender)) label = 0
            else nn.getPredictions(msgs).apply {
                senderToProbs[sender] = this
                label = toList().indexOf(maxOrNull())
            }

            saveThread?.join()
            saveThread = Thread { saveMessage(ind, sender, messages[sender]!!, label) }
            saveThread.start()
            updateProgress(msgs.size)
        }
        saveThread?.join()

        mmsThread.join()
        finish()
    }
}