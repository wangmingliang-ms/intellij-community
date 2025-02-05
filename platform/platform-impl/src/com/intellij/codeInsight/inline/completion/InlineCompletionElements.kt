// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.inline.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiUtilBase
import com.intellij.util.concurrency.annotations.RequiresBlockingContext
import org.jetbrains.annotations.ApiStatus

private typealias EditorCaret = Caret

@ApiStatus.Experimental
data class InlineCompletionRequest(
  val event: InlineCompletionEvent,

  val file: PsiFile,
  val editor: Editor,
  val document: Document,
  val startOffset: Int,
  val endOffset: Int,
  val lookupElement: LookupElement? = null,
) : UserDataHolderBase()

/**
 * Be aware that creating your own event is unsafe for a while and might face compatibility issues
 */
@ApiStatus.Experimental
interface InlineCompletionEvent {

  @RequiresBlockingContext
  fun toRequest(): InlineCompletionRequest?

  /**
   * A class representing a direct call in the code editor by [InsertInlineCompletionAction].
   */
  @ApiStatus.Experimental
  data class DirectCall(
    val editor: Editor,
    val caret: EditorCaret,
    val context: DataContext? = null,
  ) : InlineCompletionEvent {
    override fun toRequest(): InlineCompletionRequest? {
      val offset = runReadAction { caret.offset }
      val project = editor.project ?: return null
      val file = getPsiFile(caret, project) ?: return null
      return InlineCompletionRequest(this, file, editor, editor.document, offset, offset)
    }
  }

  /**
   * Represents a non-dummy not empty document event call in the editor.
   */
  @ApiStatus.Experimental
  data class DocumentChange(val event: DocumentEvent, val editor: Editor) : InlineCompletionEvent {
    override fun toRequest(): InlineCompletionRequest? {
      val project = editor.project ?: return null
      val caretModel = editor.caretModel
      if (caretModel.caretCount != 1) return null

      val file = getPsiFile(caretModel.currentCaret, project) ?: return null
      return InlineCompletionRequest(this, file, editor, event.document, event.offset, event.offset + event.newLength)
    }
  }

  /**
   * A class representing a lookup event.
   *
   * This class implements the [InlineCompletionEvent] interface and provides a method to convert the event to a request
   * using the [toRequest] method.
   *
   * @param event The lookup event.
   */
  @ApiStatus.Experimental
  data class LookupChange(override val event: LookupEvent) : InlineLookupEvent {
    override fun toRequest(): InlineCompletionRequest? {
      return super.toRequest()?.takeIf { it.lookupElement != null }
    }
  }

  /**
   * Represents an event when a lookup is cancelled during inline completion.
   *
   * @param event The lookup event associated with the cancellation.
   */
  data class LookupCancelled(override val event: LookupEvent) : InlineLookupEvent

  sealed interface InlineLookupEvent : InlineCompletionEvent {
    val event: LookupEvent
    override fun toRequest(): InlineCompletionRequest? {
      val editor = runReadAction { event.lookup?.editor } ?: return null
      val caretModel = editor.caretModel
      if (caretModel.caretCount != 1) return null

      val project = editor.project ?: return null

      val (file, offset) = runReadAction {
        getPsiFile(caretModel.currentCaret, project) to caretModel.offset
      }
      if (file == null) return null

      return InlineCompletionRequest(this, file, editor, editor.document, offset, offset, event.item)
    }
  }
}

@RequiresBlockingContext
private fun getPsiFile(caret: Caret, project: Project): PsiFile? {
  return runReadAction {
    val file = PsiDocumentManager.getInstance(project).getPsiFile(caret.editor.document) ?: return@runReadAction null
    // * [PsiUtilBase] takes into account injected [PsiFile] (like in Jupyter Notebooks)
    // * However, it loads a file into the memory, which is expensive
    // * Some tests forbid loading a file when tearing down
    // * On tearing down, Lookup Cancellation happens, which causes the event
    // * Existence of [treeElement] guarantees that it's in the memory
    if (file.isLoadedInMemory()) {
      PsiUtilBase.getPsiFileInEditor(caret, project)
    }
    else {
      file
    }
  }
}

private fun PsiFile.isLoadedInMemory(): Boolean {
  return (this as? PsiFileImpl)?.treeElement != null
}
