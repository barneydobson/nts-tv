package io.github.barneydobson.ntstv

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** The horizontal row of Infinite Mixtapes tiles. */
class MixtapeAdapter(
    private val onClick: (Mixtape) -> Unit,
    private val onFocus: (Mixtape?) -> Unit,
) : RecyclerView.Adapter<MixtapeAdapter.Holder>() {

    private var items: List<Mixtape> = emptyList()
    private var playingAlias: String? = null
    private var playState: PlayState? = null

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Mixtape>) {
        if (list == items) return
        items = list.distinctBy { it.alias }
        notifyDataSetChanged()
    }

    fun setPlaying(alias: String?, state: PlayState?) {
        val newState = if (alias == null) null else state
        if (alias == playingAlias && newState == playState) return
        playingAlias = alias
        playState = newState
        notifyItemRangeChanged(0, items.size, STATE_PAYLOAD)
    }

    override fun getItemCount() = items.size

    // Unique per alias for the life of the adapter (hashCode() can collide).
    private val ids = HashMap<String, Long>()

    override fun getItemId(position: Int): Long =
        ids.getOrPut(items[position].alias) { ids.size.toLong() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_mixtape, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
        holder.bindState()
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) onBindViewHolder(holder, position) else holder.bindState()
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.art)
        private val title: TextView = view.findViewById(R.id.title)
        private val badge: TextView = view.findViewById(R.id.badge)
        private var item: Mixtape? = null

        init {
            art.clipToOutline = true
            view.setOnClickListener { item?.let(onClick) }
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animateFocus(hasFocus, 1.08f)
                onFocus(if (hasFocus) item else null)
            }
        }

        fun bind(mixtape: Mixtape) {
            item = mixtape
            title.text = mixtape.title
            art.load(mixtape.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.art_placeholder)
                error(R.drawable.art_placeholder)
            }
        }

        fun bindState() {
            val playing = item != null && item?.alias == playingAlias
            itemView.isActivated = playing
            badge.isVisible = playing
            badge.setText(
                when (playState) {
                    PlayState.BUFFERING -> R.string.badge_buffering
                    PlayState.PAUSED -> R.string.badge_paused
                    PlayState.ERROR -> R.string.badge_error
                    else -> R.string.badge_playing
                },
            )
        }
    }

    private companion object {
        val STATE_PAYLOAD = Any()
    }
}
