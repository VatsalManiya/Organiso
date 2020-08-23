package com.bruhascended.sms

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.room.Room
import androidx.viewpager.widget.ViewPager
import com.bruhascended.sms.data.ContactsManager
import com.bruhascended.sms.data.labelText
import com.bruhascended.sms.db.Conversation
import com.bruhascended.sms.db.ConversationDatabase
import com.bruhascended.sms.services.SMSReceiver
import com.bruhascended.sms.ui.main.ConversationListViewAdaptor
import com.bruhascended.sms.ui.main.MainViewModel
import com.bruhascended.sms.ui.main.SectionsPagerAdapter
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.searchListView
import kotlinx.android.synthetic.main.activity_main.searchEditText
import kotlinx.android.synthetic.main.activity_main.searchLayout
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList

lateinit var mainViewModel: MainViewModel
fun isMainViewModelNull() = !(::mainViewModel.isInitialized)

class MainActivity : AppCompatActivity() {
    private lateinit var mContext: Context

    private var searchLayoutVisible = false
    private var inputManager: InputMethodManager? = null


    private fun showSearchLayout() {
        searchLayoutVisible = true
        appBar.visibility = View.INVISIBLE
        searchLayout.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        searchEditText.requestFocus()
                        inputManager?.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
                    }
                })
        }
        fab.visibility = View.GONE

        searchEditText.doOnTextChanged { _, _, _, _ ->
            val key = searchEditText.text.toString().trim().toLowerCase(Locale.ROOT)
            progress.visibility = View.VISIBLE
            val res = ArrayList<Conversation>()

            var recyclerViewState: Parcelable
            for (i in 0..3) {
                res.addAll(mainViewModel.daos[i].findBySender("%${key}%"))
                recyclerViewState = searchListView.onSaveInstanceState()!!
                searchListView.adapter = ConversationListViewAdaptor(mContext, res as List<Conversation>)
                searchListView.onRestoreInstanceState(recyclerViewState)
                searchListView.visibility = View.VISIBLE
                progress.visibility = View.GONE
            }
            searchListView.onItemClickListener =
                AdapterView.OnItemClickListener { _: AdapterView<*>, _: View, i: Int, _: Long ->
                    val intent = Intent(mContext, ConversationActivity::class.java)
                    intent.putExtra("ye", res[i])
                    startActivity(intent)
                }

            info.text = getString(R.string.no_matches)

            if (res.isEmpty()) info.visibility = TextView.VISIBLE
            else info.visibility = TextView.GONE
        }
    }

    private fun hideSearchLayout() {
        searchLayoutVisible = false
        appBar.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(300).start()
        }
        searchLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    inputManager?.hideSoftInputFromWindow(mBackButton.windowToken, 0)
                    searchLayout.visibility = View.GONE
                    GlobalScope.launch {
                        delay(300)
                        runOnUiThread{
                            fab.visibility = View.VISIBLE
                        }
                    }
                    searchEditText.setText("")
                }
            }).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startService(Intent(this, SMSReceiver::class.java))

        mContext = this
        mainViewModel = MainViewModel()

        mainViewModel.daos = Array(6) {
            Room.databaseBuilder(
                mContext, ConversationDatabase::class.java,
                mContext.resources.getString(labelText[it])
            ).allowMainThreadQueries().build().manager()
        }

        Thread {
            mainViewModel.contacts.postValue(ContactsManager(mContext).getContactsList())
        }.start()

        setContentView(R.layout.activity_main)

        inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        setSupportActionBar(mToolbar)

        viewPager.adapter = SectionsPagerAdapter(this, supportFragmentManager)
        viewPager.offscreenPageLimit = 3
        viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (position != mainViewModel.selection.value) {
                    mainViewModel.selection.postValue(-1)
                }
            }
        })
        tabs.setupWithViewPager(viewPager)

        fab.setOnClickListener {
            startActivity(Intent(mContext, NewConversationActivity::class.java))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> {
                showSearchLayout()
                mBackButton.setOnClickListener{ hideSearchLayout() }
            }
            R.id.action_spam -> {
                val intent = Intent(mContext, ExtraCategoryActivity::class.java)
                intent.putExtra("Type", 4)
                startActivity(intent)
            }
            R.id.action_block -> {
                val intent = Intent(mContext, ExtraCategoryActivity::class.java)
                intent.putExtra("Type", 5)
                startActivity(intent)
            }
        }
        return true
    }

    override fun onBackPressed() {
        if (searchLayoutVisible) hideSearchLayout()
        else super.onBackPressed()
    }
}