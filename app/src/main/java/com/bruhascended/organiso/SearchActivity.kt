package com.bruhascended.organiso

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bruhascended.organiso.ConversationActivity.Companion.EXTRA_CONVERSATION
import com.bruhascended.organiso.ConversationActivity.Companion.EXTRA_MESSAGE_ID
import com.bruhascended.core.db.ContactsProvider
import com.bruhascended.core.db.Conversation
import com.bruhascended.core.db.Message
import com.bruhascended.core.db.MessageDbFactory
import com.bruhascended.core.db.MainDaoProvider
import com.bruhascended.organiso.common.ScrollEffectFactory
import com.bruhascended.organiso.ui.search.SearchRecyclerAdaptor
import com.bruhascended.organiso.ui.search.SearchResultViewHolder.ResultItem
import com.google.gson.Gson
import kotlinx.android.synthetic.main.activity_search.*
import java.util.*

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

class SearchActivity : AppCompatActivity() {

    companion object {
        const val TYPE_HEADER = 4
        const val TYPE_CONVERSATION = 0
        const val TYPE_CONTACT = 1
        const val TYPE_MESSAGE_SENT = 2
        const val TYPE_MESSAGE_RECEIVED = 3
        const val TYPE_FOOTER = 5

        const val HEADER_CONTACTS = 42
    }

    private lateinit var mContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var categories: Array<Int>
    private lateinit var mContactsProvider: ContactsProvider
    private lateinit var mAdaptor: SearchRecyclerAdaptor
    private lateinit var result: ArrayList<ResultItem>

    private var searchThread = Thread{}

    private fun showResults(key: String) {
        searchRecycler.scrollToPosition(0)
        searchRecycler.isVisible = true
        searchThread.interrupt()
        mAdaptor.searchKey = key
        mAdaptor.refresh()
        searchThread = Thread {
            val displayedSenders = arrayListOf<String>()
            for (category in categories) {
                if (searchThread.isInterrupted) return@Thread
                val cons = MainDaoProvider(mContext).getMainDaos()[category].search("$key%", "% $key%")
                if (cons.isNotEmpty()) {ResultItem(TYPE_HEADER, categoryHeader = category)
                    cons.forEach { displayedSenders.add(it.clean) }
                    searchRecycler.post {
                        mAdaptor.addItems(listOf(ResultItem(TYPE_HEADER, categoryHeader = category)))
                        mAdaptor.addItems(
                            List(cons.size) {
                                ResultItem(TYPE_CONVERSATION, conversation = cons[it])
                            }
                        )
                    }
                }
            }

            var otherDisplayed = false
            mContactsProvider.getSync().forEach {  contact ->
                val name = contact.name.toLowerCase(Locale.ROOT)
                if (!Regex("\\b${key}.*").matches(name))
                    return@forEach

                for (sender in displayedSenders) {
                    if (sender == contact.clean) {
                        return@forEach
                    }
                }

                if (!otherDisplayed) {
                    otherDisplayed = true
                    searchRecycler.post {
                        mAdaptor.addItems(listOf(ResultItem(TYPE_HEADER, categoryHeader = HEADER_CONTACTS)))
                    }
                }
                searchRecycler.post {
                    mAdaptor.addItems(listOf(ResultItem(
                        TYPE_CONTACT,
                        conversation = Conversation(contact.address, contact.clean, contact.name)
                    )))
                }
            }

            for (category in categories) {
                var isEmpty = true
                if (searchThread.isInterrupted) return@Thread
                for (con in MainDaoProvider(mContext).getMainDaos()[category].loadAllSync()) {
                    var msgs: List<Message>
                    if (searchThread.isInterrupted) return@Thread
                    MessageDbFactory(mContext).of(con.clean).apply {
                        msgs = manager().search("$key%", "% $key%")
                        close()
                    }
                    if (!msgs.isNullOrEmpty()) {
                        if (isEmpty) {
                            isEmpty = false
                            searchRecycler.post {
                                mAdaptor.addItems(
                                    listOf(ResultItem(TYPE_HEADER, categoryHeader = 10+category))
                                )
                            }
                        }
                        searchRecycler.post {
                            mAdaptor.addItems(listOf(ResultItem(TYPE_CONTACT, conversation = con)))
                            mAdaptor.addItems(
                                List(msgs.size) {
                                    ResultItem (
                                        if (msgs[it].type == 1) TYPE_MESSAGE_RECEIVED
                                        else TYPE_MESSAGE_SENT,
                                        conversation = con,
                                        message = msgs[it]
                                    )
                                }
                            )
                        }
                    }
                    if (searchThread.isInterrupted) return@Thread
                }
            }

            searchRecycler.post {
                mAdaptor.doOnLoaded()
            }
        }
        searchThread.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (prefs.getBoolean("dark_theme", false)) setTheme(R.style.DarkTheme)
        else setTheme(R.style.LightTheme)
        setContentView(R.layout.activity_search)
        searchEditText.requestFocus()

        mContext = this
        mContactsProvider = ContactsProvider(this)
        result = arrayListOf(ResultItem(TYPE_FOOTER))
        mAdaptor = SearchRecyclerAdaptor(mContext, result)
        mAdaptor.doOnConversationClick = {
            startActivity(
                Intent(mContext, ConversationActivity::class.java)
                    .putExtra(EXTRA_CONVERSATION, it)
            )
            finish()
        }

        mAdaptor.doOnMessageClick = {
            startActivity(
                Intent(mContext, ConversationActivity::class.java)
                    .putExtra(EXTRA_CONVERSATION, it.second)
                    .putExtra(EXTRA_MESSAGE_ID, it.first)
            )
            finish()
        }

        searchRecycler.adapter = mAdaptor

        val visible = Gson().fromJson(
            prefs.getString("visible_categories", ""), Array<Int>::class.java
        )
        val hidden = Gson().fromJson(
            prefs.getString("hidden_categories", ""), Array<Int>::class.java
        )
        categories = if (prefs.getBoolean("show_hidden_results", false))
            visible + hidden else visible

        clear_text.setOnClickListener{
            searchEditText.setText("")
            searchEditText.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        }

        backButton.setOnClickListener{
            onBackPressed()
        }

        searchEditText.doOnTextChanged { key, _, _, _ ->
            clear_text.visibility = if (key.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        searchRecycler.apply {
            layoutManager = LinearLayoutManager(mContext).apply {
                orientation = LinearLayoutManager.VERTICAL
            }
            edgeEffectFactory = ScrollEffectFactory()
            addOnScrollListener(ScrollEffectFactory.OnScrollListener())
        }

        searchEditText.setOnEditorActionListener { _, i, _ ->
            if (i != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener true
            val key = searchEditText.text.toString().trim().toLowerCase(Locale.ROOT)

            searchRecycler.isVisible = !key.isBlank()
            if (key.isBlank()) {
                return@setOnEditorActionListener true
            } else {
                showResults(key)
            }

            true
        }
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        if (intent.action != null) startActivityIfNeeded(
            Intent(mContext, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), 0
        )
        finish()
        overridePendingTransition(R.anim.hold, android.R.anim.fade_out)
    }
}