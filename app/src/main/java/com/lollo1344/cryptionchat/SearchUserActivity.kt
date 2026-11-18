package com.lollo1344.cryptionchat

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lollo1344.cryptionchat.databinding.ActivitySearchUserBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SearchUserActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchUserBinding
    private lateinit var userSearchAdapter: UserSearchAdapter
    private var currentUserId: Int = -1
    private var currentUsername: String = ""

    // Job per gestire il delay (debounce) della ricerca in tempo reale
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isBlocked || isFinishing) return
        binding = ActivitySearchUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = SecurePrefs.get(this)
        currentUserId = prefs.getInt("user_id", -1)
        currentUsername = prefs.getString("username", "") ?: ""

        if (currentUserId == -1) {
            Toast.makeText(this, "Errore: sessione utente non valida.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Imposta la toolbar come ActionBar per poterci agganciare la SearchView
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Cerca Utenti"

        setupRecyclerView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_search_user, menu)
        val searchItem = menu.findItem(R.id.action_search_active)

        // FIX CRASH: Uso il cast sicuro "as?" e verifico che non sia null
        val searchView = searchItem?.actionView as? SearchView

        if (searchView != null) {
            searchView.queryHint = "Scrivi username..."

            // Espandi automaticamente la barra di ricerca all'apertura dell'Activity
            searchItem.expandActionView()
            searchView.requestFocus()

            // Se l'utente chiude la SearchView, chiudiamo l'intera Activity per tornare alla Home
            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    finish()
                    return true
                }
            })

            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    // IL PANIC CODE SCATTA SOLO QUANDO SI PREME "CERCA/INVIO" SULLA TASTIERA
                    if (!query.isNullOrBlank()) {
                        if (PanicHelper.isPanicTriggered(this@SearchUserActivity, query)) {
                            PanicHelper.executePanicProtocol(this@SearchUserActivity)
                            return true
                        }
                        // Se non è il Panic Code, nascondiamo la tastiera per far vedere i risultati
                        searchView.clearFocus()
                    }
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    // RICERCA "LIVE" MENTRE SI SCRIVE (con debounce di 300ms)
                    searchJob?.cancel() // Cancella la ricerca precedente se l'utente sta scrivendo velocemente
                    searchJob = lifecycleScope.launch {
                        delay(300) // Aspetta 300 millisecondi prima di fare la richiesta
                        if (!newText.isNullOrBlank()) {
                            searchForUsers(newText)
                        } else {
                            // Se svuota la barra, ripulisci i risultati
                            userSearchAdapter.updateUsers(emptyList())
                            binding.textViewEmptySearch.visibility = View.GONE
                        }
                    }
                    return true
                }
            })
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        userSearchAdapter = UserSearchAdapter(emptyList()) { user ->
            startOrGetChatWith(user)
        }
        binding.recyclerViewUsers.apply {
            adapter = userSearchAdapter
            layoutManager = LinearLayoutManager(this@SearchUserActivity)
        }
    }

    private fun searchForUsers(query: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.textViewEmptySearch.visibility = View.GONE
        binding.recyclerViewUsers.visibility = View.GONE

        ApiClient.instance.searchUsers(userId = currentUserId, query = query).enqueue(object : Callback<SearchUserResponse> {
            override fun onResponse(call: Call<SearchUserResponse>, response: Response<SearchUserResponse>) {
                if (isFinishing || isDestroyed) return
                binding.progressBar.visibility = View.GONE

                if (response.isSuccessful && response.body()?.status == "success") {
                    val users = response.body()?.data ?: emptyList()
                    if (users.isEmpty()) {
                        binding.textViewEmptySearch.text = "Nessun utente trovato."
                        binding.textViewEmptySearch.visibility = View.VISIBLE
                    } else {
                        binding.recyclerViewUsers.visibility = View.VISIBLE
                        userSearchAdapter.updateUsers(users)
                    }
                } else {
                    binding.textViewEmptySearch.text = "Nessun risultato."
                    binding.textViewEmptySearch.visibility = View.VISIBLE
                }
            }

            override fun onFailure(call: Call<SearchUserResponse>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                binding.progressBar.visibility = View.GONE
                binding.textViewEmptySearch.text = "Errore di rete: ${t.message}"
                binding.textViewEmptySearch.visibility = View.VISIBLE
            }
        })
    }

    private fun startOrGetChatWith(user: User) {
        Toast.makeText(this, "Avvio chat con ${user.username}...", Toast.LENGTH_SHORT).show()
        ApiClient.instance.startOrGetPrivateChat(user1Id = currentUserId, user2Id = user.id).enqueue(object : Callback<StartChatResponse> {
            override fun onResponse(call: Call<StartChatResponse>, response: Response<StartChatResponse>) {
                if (response.isSuccessful && response.body()?.status == "success") {
                    val chatInfo = response.body()?.data
                    if (chatInfo != null) {
                        val intent = Intent(this@SearchUserActivity, ChatActivity::class.java).apply {
                            putExtra("GROUP_ID", chatInfo.groupId)
                            putExtra("GROUP_NAME", chatInfo.groupName)
                            putExtra("USER_ID", currentUserId)
                            putExtra("USERNAME", currentUsername)
                            putExtra("IS_PRIVATE_CHAT", chatInfo.isPrivateChat)
                        }
                        startActivity(intent)
                        finish() // Chiude la schermata di ricerca dopo aver avviato la chat
                    } else {
                        Toast.makeText(this@SearchUserActivity, "Errore: dati della chat non ricevuti.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@SearchUserActivity, "Errore: ${response.body()?.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<StartChatResponse>, t: Throwable) {
                Toast.makeText(this@SearchUserActivity, "Errore di rete: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }
}