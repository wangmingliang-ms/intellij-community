// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.impl.presentationAssistant

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.observable.util.addMouseHoverListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.WindowMoveListener
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.hover.HoverListener
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.Alarm
import com.intellij.util.ui.Animator
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

internal class ActionInfoPopupGroup(val project: Project, textFragments: List<TextData>, showAnimated: Boolean) : Disposable {
  data class ActionBlock(val popup: JBPopup, val panel: ActionInfoPanel) {
    val isDisposed: Boolean get() = popup.isDisposed
  }

  private val configuration = PresentationAssistant.INSTANCE.configuration
  private val appearance = appearanceFromSize(PresentationAssistantPopupSize.from(configuration.popupSize))
  private val actionBlocks = textFragments.map { fragment ->
    val panel = ActionInfoPanel(fragment, appearance)
    val popup = createPopup(panel, showAnimated)
    ActionBlock(popup, panel)
  }
  private val settingsButton = PresentationAssistantQuickSettingsButton(project, appearance) { isSettingsButtonForcedToBeShown = (it > 0) }

  private var isPopupHovered: Boolean = false
    set(value) {
      val oldValue = field
      field = value

      if (oldValue != isPopupHovered) {
        if (isPopupHovered) {
          settingsButton.acquireShownStateRequest(computeLocation(project, actionBlocks.size))
        }
        else {
          settingsButton.releaseShownStateRequest()
        }
      }
      updateForcedToBeShown()
    }

  private var isSettingsButtonForcedToBeShown: Boolean = true
    set(value) {
      field = value
      updateForcedToBeShown()
    }

  private var forcedToBeShown: Boolean = false
    set(value) {
      if (value != field) {
        if (value) hideAlarm.cancelAllRequests()
        else if (isShown) resetHideAlarm()
      }
      field = value
    }

  private val hideAlarm = Alarm(this)
  private var animator: Animator
  var phase = Phase.FADING_IN
    private set
  val isShown: Boolean get() = phase == Phase.SHOWN

  enum class Phase { FADING_IN, SHOWN, FADING_OUT, HIDDEN }

  init {
    val connect = ApplicationManager.getApplication().getMessageBus().connect(this)
    connect.subscribe<LafManagerListener>(LafManagerListener.TOPIC, LafManagerListener { updatePopupsBounds(project) })

    animator = FadeInOutAnimator(true, showAnimated)
    actionBlocks.mapIndexed { index, block ->
      block.popup.show(computeLocation(project, index))
    }

    if (showAnimated) {
      animator.resume()
    }
    else {
      phase = Phase.SHOWN
    }

    resetHideAlarm()
  }

  private fun createPopup(panel: ActionInfoPanel, hiddenInitially: Boolean): JBPopup {
    val popup = with(JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)) {
      if (hiddenInitially) setAlpha(1.0.toFloat())
      setFocusable(false)
      setBelongsToGlobalPopupStack(false)
      setCancelKeyEnabled(false)
      setCancelCallback { phase = Phase.HIDDEN; true }
      createPopup()
    }

    popup.content.background = ActionInfoPanel.BACKGROUND

    popup.addListener(object : JBPopupListener {
      override fun beforeShown(lightweightWindowEvent: LightweightWindowEvent) {}
      override fun onClosed(lightweightWindowEvent: LightweightWindowEvent) {
        phase = Phase.HIDDEN
      }
    })

    popup.content.addMouseHoverListener(this, object: HoverListener() {
      override fun mouseEntered(component: Component, x: Int, y: Int) { isPopupHovered = true }
      override fun mouseMoved(component: Component, x: Int, y: Int) { isPopupHovered = true }
      override fun mouseExited(component: Component) { isPopupHovered = false }
    })

    val moveListener = object : WindowMoveListener(panel) {
      override fun mouseDragged(event: MouseEvent?) {
        super.mouseDragged(event)
        val actionInfoPanel = event?.component as? ActionInfoPanel?: return
        saveLocationDelta(project, actionInfoPanel)
        updatePopupsBounds(project, actionInfoPanel)
      }
    }.installTo(panel)
    Disposer.register(this) { moveListener.uninstallFrom(panel) }

    return popup
  }

  fun updateText(project: Project, textFragments: List<TextData>) {
    if (actionBlocks.any { it.isDisposed }) return

    actionBlocks.mapIndexed { index, block ->
      block.panel.textData = textFragments[index]
    }

    updatePopupsBounds(project)
    resetHideAlarm()
  }

  private fun updatePopupsBounds(project: Project, ignoredPanel: ActionInfoPanel? = null) {
    actionBlocks.mapIndexed { index, actionBlock ->
      if (actionBlock.panel == ignoredPanel) return@mapIndexed

      actionBlock.popup.content.let {
        it.validate()
        it.repaint()
      }

      val newBounds = Rectangle(computeLocation(project, index).screenPoint, actionBlock.panel.preferredSize)
      actionBlock.popup.setBounds(newBounds)
    }

    settingsButton.hidePopup()
    settingsButton.updatePreferredSize()
    showFinalAnimationFrame()
  }

  private fun saveLocationDelta(project: Project, panel: ActionInfoPanel?) {
    panel ?: return
    val index = actionBlocks.indexOfFirst { it.panel == panel }
    if (index < 0) return

    val window = getPopupWindow(actionBlocks[index].popup) ?: return
    val originalLocation = computeLocation(project, index, true).screenPoint

    configuration.deltaX = JBUI.unscale(window.x - originalLocation.x)
    configuration.deltaY = JBUI.unscale(window.y - originalLocation.y)
  }

  fun close() {
    Disposer.dispose(this)
  }

  override fun dispose() {
    phase = Phase.HIDDEN
    actionBlocks.forEach {
      if (!it.popup.isDisposed) {
        it.popup.cancel()
      }
    }
    Disposer.dispose(animator)
    Disposer.dispose(settingsButton)
  }

  fun canBeReused(size: Int): Boolean = size == actionBlocks.size && (phase == Phase.FADING_IN || phase == Phase.SHOWN)

  private fun getPopupWindows(): List<Window> = actionBlocks.mapNotNull { actionBlock ->
    getPopupWindow(actionBlock.popup)
  }

  private fun getPopupWindow(popup: JBPopup): Window? {
    if (popup.isDisposed) return null
    val window = SwingUtilities.windowForComponent(popup.content)
    if (window != null && window.isShowing) return window
    return null
  }

  private fun setAlpha(alpha: Float) {
    getPopupWindows().forEach {
      WindowManager.getInstance().setAlphaModeRatio(it, alpha)
    }
  }

  private fun resetHideAlarm() {
    hideAlarm.cancelAllRequests()
    hideAlarm.addRequest({ fadeOut() }, configuration.popupDuration, ModalityState.any())
  }

  private fun showFinalAnimationFrame() {
    phase = Phase.SHOWN
    setAlpha(0f)
  }

  private fun fadeOut() {
    if (phase != Phase.SHOWN) return
    phase = Phase.FADING_OUT
    Disposer.dispose(animator)
    animator = FadeInOutAnimator(false, true)
    animator.resume()
  }

  private fun updateForcedToBeShown() {
    forcedToBeShown = isPopupHovered || isSettingsButtonForcedToBeShown
  }

  private fun computeLocation(project: Project, index: Int?, ignoreDelta: Boolean = false): RelativePoint {
    val preferredSizes = actionBlocks.map { it.panel.preferredSize }
    val gap = JBUIScale.scale(appearance.spaceBetweenPopups)
    val popupGroupSize: Dimension = if (actionBlocks.isNotEmpty()) {
      val totalWidth = preferredSizes.sumOf { it.width } + (gap * (preferredSizes.size - 1))
      Dimension(totalWidth, preferredSizes.first().height)
    }
    else Dimension()

    val ideFrame = WindowManager.getInstance().getIdeFrame(project)!!
    val visibleRect = ideFrame.component.visibleRect
    val margin = JBUIScale.scale(60)
    val deltaX = if (ignoreDelta) 0 else configuration.deltaX?.let { JBUIScale.scale(it) } ?: 0
    val deltaY = if (ignoreDelta) 0 else configuration.deltaY?.let { JBUIScale.scale(it) } ?: 0

    val x = when (configuration.horizontalAlignment) {
      0 -> visibleRect.x + margin
      1 -> visibleRect.x + (visibleRect.width - popupGroupSize.width) / 2
      else -> visibleRect.x + visibleRect.width - popupGroupSize.width - margin
    } + (index?.takeIf {
      0 < index && index <= actionBlocks.size
    }?.let {
      // Calculate X for particular popup
      (0..<index).map { preferredSizes[it].width }.reduce { total, width ->
        total + width
      } + gap * index
    } ?: 0) + deltaX

    val y = when (configuration.verticalAlignment) {
      0 -> visibleRect.y + margin
      else -> visibleRect.y + visibleRect.height - popupGroupSize.height - margin
    } + deltaY

    return RelativePoint(ideFrame.component, Point(x, y))
  }

  inner class FadeInOutAnimator(private val forward: Boolean, animated: Boolean) : Animator("Action Hint Fade In/Out", 8, if (animated) 100 else 0, false, forward) {
    override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
      if (forward && phase != Phase.FADING_IN
          || !forward && phase != Phase.FADING_OUT) return
      setAlpha((totalFrames - frame).toFloat() / totalFrames)
    }

    override fun paintCycleEnd() {
      if (forward) {
        showFinalAnimationFrame()
      }
      else {
        close()
      }
    }
  }

  internal data class Appearance(val titleFontSize: Float,
                                 val subtitleFontSize: Float,
                                 val titleInsets: JBInsets,
                                 val subtitleInsets: JBInsets,
                                 val spaceBetweenPopups: Int,
                                 val titleSubtitleGap: Int,
                                 val settingsButtonWidth: Int)

  companion object {
    private fun appearanceFromSize(popupSize: PresentationAssistantPopupSize): Appearance = when(popupSize) {
      PresentationAssistantPopupSize.SMALL -> Appearance(22f,
                                                         12f,
                                                         JBInsets(6, 12, 0, 12),
                                                         JBInsets(0, 14, 6, 14),
                                                         8,
                                                         1,
                                                         25)

      PresentationAssistantPopupSize.MEDIUM -> Appearance(32f,
                                                          13f,
                                                          JBInsets(6, 16, 0, 16),
                                                          JBInsets(0, 18, 8, 18),
                                                          12,
                                                          -2,
                                                          30)

      PresentationAssistantPopupSize.LARGE -> Appearance(40f,
                                                         14f,
                                                         JBInsets(6, 16, 0, 16),
                                                         JBInsets(0, 18, 8, 18),
                                                         12,
                                                         -2,
                                                         34)
    }
  }
}
