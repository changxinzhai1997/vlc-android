/*
 * ************************************************************************
 *  MoreFragment.kt
 * *************************************************************************
 * Copyright © 2020 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.gui

import android.content.Intent
import android.os.Bundle
import android.util.SparseBooleanArray
import android.view.*
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.android.synthetic.main.more_fragment.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.flow.onEach
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.ACTIVITY_RESULT_PREFERENCES
import org.videolan.tools.*
import org.videolan.vlc.R
import org.videolan.vlc.gui.SecondaryActivity.Companion.ABOUT
import org.videolan.vlc.gui.helpers.*
import org.videolan.vlc.gui.preferences.PreferencesActivity
import org.videolan.vlc.interfaces.IHistory
import org.videolan.vlc.interfaces.IRefreshable
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.media.PlaylistManager
import org.videolan.vlc.util.launchWhenStarted
import org.videolan.vlc.viewmodels.HistoryModel

private const val TAG = "VLC/HistoryFragment"
private const val KEY_SELECTION = "key_selection"

@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class MoreFragment : BaseFragment(), IRefreshable, IHistory, SwipeRefreshLayout.OnRefreshListener {

    private lateinit var viewModel: HistoryModel
    private lateinit var cleanMenuItem: MenuItem
    private lateinit var multiSelectHelper: MultiSelectHelper<MediaWrapper>
    private val historyAdapter: HistoryAdapter = HistoryAdapter(true)
    override fun hasFAB() = false
    fun getMultiHelper(): MultiSelectHelper<HistoryModel>? = historyAdapter.multiSelectHelper as? MultiSelectHelper<HistoryModel>
    private var savedSelection = SparseBooleanArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (savedInstanceState?.getParcelable<SparseBooleanArrayParcelable>(KEY_SELECTION))?.let { savedSelection = it.data }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.more_fragment, container, false)
    }

    override fun getTitle() = getString(R.string.history)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProviders.of(requireActivity(), HistoryModel.Factory(requireContext())).get(HistoryModel::class.java)
        viewModel.dataset.observe(viewLifecycleOwner, Observer<List<MediaWrapper>> { list ->
            list?.let {
                historyAdapter.update(it)
                if (::cleanMenuItem.isInitialized) {
                    cleanMenuItem.isVisible = list.isNotEmpty()
                }
                if (list.isEmpty()) historyTitle.setGone() else historyTitle.setVisible()
            }
            restoreMultiSelectHelper()
        })
        viewModel.loading.observe(viewLifecycleOwner) {
            (activity as? MainActivity)?.refreshing = it
        }

        settingsButton.setOnClickListener {
            requireActivity().startActivityForResult(Intent(activity, PreferencesActivity::class.java), ACTIVITY_RESULT_PREFERENCES)
        }
        aboutButton.setOnClickListener {
            val i = Intent(activity, SecondaryActivity::class.java)
            i.putExtra("fragment", ABOUT)
            requireActivity().startActivityForResult(i, SecondaryActivity.ACTIVITY_RESULT_SECONDARY)
        }
        historyAdapter.updateEvt.observe(viewLifecycleOwner) {
            swipeRefreshLayout.isRefreshing = false
            //restoreMultiSelectHelper()
        }
        historyAdapter.events.onEach { it.process() }.launchWhenStarted(lifecycleScope)
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
        (activity as? ContentActivity)?.setTabLayoutVisibility(false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        historyList.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        historyList.adapter = historyAdapter
        historyList.nextFocusUpId = R.id.ml_menu_search
        historyList.nextFocusLeftId = android.R.id.list
        historyList.nextFocusRightId = android.R.id.list
        historyList.nextFocusForwardId = android.R.id.list

        multiSelectHelper = historyAdapter.multiSelectHelper
        historyList.requestFocus()
        registerForContextMenu(historyList)
        swipeRefreshLayout.setOnRefreshListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        getMultiHelper()?.let {
            outState.putParcelable(KEY_SELECTION, SparseBooleanArrayParcelable(it.selectionMap))
        }
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_option_history, menu)
        super.onCreateOptionsMenu(menu, inflater)
        cleanMenuItem = menu.findItem(R.id.ml_menu_clean)
        cleanMenuItem.isVisible = !isEmpty()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.ml_menu_clean -> {
                clearHistory()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun refresh() = viewModel.refresh()

    override fun onRefresh() {
        refresh()
    }

    override fun isEmpty(): Boolean {
        return historyAdapter.isEmpty()
    }

    override fun clearHistory() {
        viewModel.clearHistory()
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.action_mode_history, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val selectionCount = multiSelectHelper.getSelectionCount()
        if (selectionCount == 0) {
            stopActionMode()
            return false
        }
        menu.findItem(R.id.action_history_info).isVisible = selectionCount == 1
        menu.findItem(R.id.action_history_append).isVisible = PlaylistManager.hasMedia()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        if (!isStarted()) return false
        val selection = multiSelectHelper.getSelection()
        if (selection.isNotEmpty()) {
            when (item?.itemId) {
                R.id.action_history_play -> MediaUtils.openList(activity, selection, 0)
                R.id.action_history_append -> MediaUtils.appendMedia(activity, selection)
                R.id.action_history_info -> showInfoDialog(selection[0])
                else -> {
                    stopActionMode()
                    return false
                }
            }
        }
        stopActionMode()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        actionMode = null
        multiSelectHelper.clearSelection()
    }

    fun restoreMultiSelectHelper() {
        getMultiHelper()?.let {

            if (savedSelection.size() > 0) {
                var hasOneSelected = false
                for (i in 0 until savedSelection.size()) {

                    it.selectionMap.append(savedSelection.keyAt(i), savedSelection.valueAt(i))
                    if (savedSelection.valueAt(i)) hasOneSelected = true
                }
                if (hasOneSelected) startActionMode()
                savedSelection.clear()
            }
        }
    }

    private fun Click.process() {
        val item = viewModel.dataset.get(position)
        when (this) {
            is SimpleClick -> onClick(position, item)
            is LongClick -> onLongClick(position, item)
            is ImageClick -> {
                if (actionMode != null) onClick(position, item)
                else onLongClick(position, item)
            }
        }
    }

    fun onClick(position: Int, item: MediaWrapper) {
        if (KeyHelper.isShiftPressed && actionMode == null) {
            onLongClick(position, item)
            return
        }
        if (actionMode != null) {
            multiSelectHelper.toggleSelection(position)
            historyAdapter.notifyItemChanged(position, item)
            invalidateActionMode()
            return
        }
        if (position != 0) viewModel.moveUp(item)
        MediaUtils.openMedia(requireContext(), item)
    }

    fun onLongClick(position: Int, item: MediaWrapper) {
        multiSelectHelper.toggleSelection(position, true)
        historyAdapter.notifyItemChanged(position, item)
        if (actionMode == null) startActionMode()
        invalidateActionMode()
    }
}
