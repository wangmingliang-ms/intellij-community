// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.ui.review

import com.intellij.collaboration.async.*
import com.intellij.collaboration.ui.codereview.diff.DiffLineLocation
import com.intellij.collaboration.ui.codereview.diff.DiscussionsViewOption
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.diff.util.LineRange
import com.intellij.diff.util.Range
import com.intellij.diff.util.Side
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchHunkUtil
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.project.Project
import com.intellij.util.childScope
import git4idea.changes.GitTextFilePatchWithHistory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.plugins.gitlab.api.dto.GitLabUserDTO
import org.jetbrains.plugins.gitlab.mergerequest.api.dto.DiffPathsInput
import org.jetbrains.plugins.gitlab.mergerequest.api.dto.GitLabDiffPositionInput
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabDiscussion
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeRequest
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabNote
import org.jetbrains.plugins.gitlab.ui.comment.*

private typealias DiscussionsFlow = Flow<Collection<GitLabMergeRequestDiffDiscussionViewModel>>
private typealias NewDiscussionsFlow = Flow<Map<DiffLineLocation, NewGitLabNoteViewModel>>

interface GitLabMergeRequestChangeViewModel {
  val isCumulativeChange: Boolean
  val changedRanges: List<Range>

  val discussions: DiscussionsFlow
  val draftDiscussions: DiscussionsFlow
  val newDiscussions: NewDiscussionsFlow

  val avatarIconsProvider: IconsProvider<GitLabUserDTO>
  val discussionsViewOption: StateFlow<DiscussionsViewOption>

  fun requestNewDiscussion(location: DiffLineLocation, focus: Boolean)
  fun cancelNewDiscussion(location: DiffLineLocation)

  fun getOriginalContent(range: LineRange): String?
}

private val LOG = logger<GitLabMergeRequestChangeViewModel>()

internal class GitLabMergeRequestChangeViewModelImpl(
  project: Project,
  parentCs: CoroutineScope,
  private val currentUser: GitLabUserDTO,
  private val mergeRequest: GitLabMergeRequest,
  private val diffData: GitTextFilePatchWithHistory,
  override val avatarIconsProvider: IconsProvider<GitLabUserDTO>,
  override val discussionsViewOption: StateFlow<DiscussionsViewOption>,
  private val contextDiscussionMappingSide: Side = Side.LEFT
) : GitLabMergeRequestChangeViewModel {

  private val cs = parentCs.childScope(Dispatchers.Default + CoroutineName("GitLab Merge Request Review Diff Change"))

  override val isCumulativeChange: Boolean = diffData.isCumulative

  override val changedRanges: List<Range> = diffData.patch.hunks.withoutContext().toList()

  override val discussions: DiscussionsFlow = mergeRequest.discussions
    .throwFailure()
    .mapCaching(
      GitLabDiscussion::id,
      { disc ->
        GitLabMergeRequestDiffDiscussionViewModelImpl(project, this, diffData, currentUser, disc, discussionsViewOption,
                                                      mergeRequest.glProject, contextDiscussionMappingSide)
      },
      GitLabMergeRequestDiffDiscussionViewModelImpl::destroy
    )
    .modelFlow(cs, LOG)

  override val draftDiscussions: DiscussionsFlow = mergeRequest.draftNotes
    .throwFailure()
    .mapFiltered { it.discussionId == null }
    .mapCaching(
      GitLabNote::id,
      { note ->
        GitLabMergeRequestDiffDraftDiscussionViewModel(project, this, diffData, note,
                                                       mergeRequest.glProject, contextDiscussionMappingSide)
      },
      GitLabMergeRequestDiffDraftDiscussionViewModel::destroy
    )
    .modelFlow(cs, LOG)


  private val _newDiscussions = MutableStateFlow<Map<DiffLineLocation, NewGitLabNoteViewModel>>(emptyMap())
  override val newDiscussions: NewDiscussionsFlow = _newDiscussions.asStateFlow()


  override fun requestNewDiscussion(location: DiffLineLocation, focus: Boolean) {
    _newDiscussions.updateAndGet {
      if (!it.containsKey(location)) {
        val vm = DelegatingGitLabNoteEditingViewModel(cs, "") {
          createDiscussion(location, it)
        }.apply {
          onDoneIn(cs) {
            cancelNewDiscussion(location)
          }
        }.forNewNote(currentUser)
        it + (location to vm)
      }
      else {
        it
      }
    }.apply {
      if (focus) {
        get(location)?.requestFocus()
      }
    }
  }

  private suspend fun createDiscussion(location: DiffLineLocation, body: String) {
    val patch = diffData.patch
    val startSha = patch.beforeVersionId!!
    val headSha = patch.afterVersionId!!
    val baseSha = if (diffData.isCumulative) diffData.fileHistory.findStartCommit() else startSha

    // Due to https://gitlab.com/gitlab-org/gitlab/-/issues/325161 we need line index for both sides for context lines
    val otherSide = transferToOtherSide(patch, location)
    val lineBefore = if (location.first == Side.LEFT) location.second else otherSide
    val lineAfter = if (location.first == Side.RIGHT) location.second else otherSide

    val pathBefore = patch.beforeName
    val pathAfter = patch.afterName

    // Due to https://gitlab.com/gitlab-org/gitlab/-/issues/296829 we need base ref here
    val positionInput = GitLabDiffPositionInput(
      baseSha,
      startSha,
      lineBefore?.inc(),
      headSha,
      lineAfter?.inc(),
      DiffPathsInput(pathBefore, pathAfter)
    )

    mergeRequest.addNote(positionInput, body)
  }

  override fun cancelNewDiscussion(location: DiffLineLocation) {
    _newDiscussions.update {
      val oldVm = it[location]
      val newMap = it - location
      cs.launch {
        oldVm?.destroy()
      }
      newMap
    }
  }

  override fun getOriginalContent(range: LineRange): String? {
    if (range.start == range.end) return ""
    return diffData.patch.hunks.find {
      it.startLineBefore <= range.start && it.endLineBefore >= range.end
    }?.let { hunk ->
      val builder = StringBuilder()
      var lineCounter = hunk.startLineBefore
      for (line in hunk.lines) {
        if (line.type == PatchLine.Type.CONTEXT) {
          lineCounter++
        }
        if (line.type == PatchLine.Type.REMOVE) {
          if (lineCounter >= range.start) {
            builder.append(line.text)
            if (!line.isSuppressNewLine) {
              builder.append("\n")
            }
          }
          lineCounter++
        }
        if (lineCounter >= range.end) break
      }
      return builder.toString()
    }
  }

  suspend fun destroy() = cs.cancelAndJoinSilently()
}

private fun transferToOtherSide(patch: TextFilePatch, location: DiffLineLocation): Int? {
  val (side, lineIndex) = location
  var lastEndBefore = 0
  var lastEndAfter = 0
  for (hunk in patch.hunks.withoutContext()) {
    when (side) {
      Side.LEFT -> {
        if (lineIndex < hunk.start1) {
          break
        }
        if (lineIndex in hunk.start1 until hunk.end1) {
          return null
        }
      }
      Side.RIGHT -> {
        if (lineIndex < hunk.start2) {
          break
        }
        if (lineIndex in hunk.start2 until hunk.end2) {
          return null
        }
      }
    }
    lastEndBefore = hunk.end1
    lastEndAfter = hunk.end2
  }
  return when (side) {
    Side.LEFT -> lastEndAfter + (lineIndex - lastEndBefore)
    Side.RIGHT -> lastEndBefore + (lineIndex - lastEndAfter)
  }
}

private fun Collection<PatchHunk>.withoutContext(): Sequence<Range> =
  asSequence().map { PatchHunkUtil.getChangeOnlyRanges(it) }.flatten()
