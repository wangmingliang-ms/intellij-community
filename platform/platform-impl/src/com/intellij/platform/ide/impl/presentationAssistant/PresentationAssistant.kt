// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

/**
 * @author nik
 */
package com.intellij.platform.ide.impl.presentationAssistant

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import org.jetbrains.annotations.Nls

enum class PresentationAssistantPopupSize(val value: Int, @Nls val displayName: String) {
  SMALL(0, IdeBundle.message("presentation.assistant.configurable.size.small")),
  MEDIUM(1, IdeBundle.message("presentation.assistant.configurable.size.medium")),
  LARGE(2, IdeBundle.message("presentation.assistant.configurable.size.large"));

  companion object {
    fun from(value: Int): PresentationAssistantPopupSize = when (value) {
      0 -> SMALL
      2 -> LARGE
      else -> MEDIUM
    }
  }
}

enum class PresentationAssistantPopupAlignment(val x: Int, val y: Int, @Nls val displayName: String) {
  TOP_LEFT(0, 0, IdeBundle.message("presentation.assistant.configurable.alignment.top.left")),
  TOP_CENTER(1, 0, IdeBundle.message("presentation.assistant.configurable.alignment.top.center")),
  TOP_RIGHT(2, 0, IdeBundle.message("presentation.assistant.configurable.alignment.top.right")),
  BOTTOM_LEFT(0, 2, IdeBundle.message("presentation.assistant.configurable.alignment.bottom.left")),
  BOTTOM_CENTER(1, 2, IdeBundle.message("presentation.assistant.configurable.alignment.bottom.center")),
  BOTTOM_RIGHT(2, 2, IdeBundle.message("presentation.assistant.configurable.alignment.bottom.right"));

  companion object {
    fun from(x: Int, y: Int): PresentationAssistantPopupAlignment = when (y) {
      0 -> when(x) {
        0 -> TOP_LEFT
        2 -> TOP_RIGHT
        else -> TOP_CENTER
      }
      else -> when(x) {
        0 -> BOTTOM_LEFT
        2 -> BOTTOM_RIGHT
        else -> BOTTOM_CENTER
      }
    }

    val defaultAlignment = BOTTOM_CENTER
  }
}

class PresentationAssistantState {
  var showActionDescriptions = false
  var popupSize: Int = 1
  var popupDuration = 4 * 1000

  /**
   * Holds the value for the horizontal alignment.
   *
   * Valid values:
   *  - 0: Aligns the element to the left.
   *  - 1: Aligns the element to the center.
   *  - 2: Aligns the element to the right.
   */
  var horizontalAlignment = 1

  /**
   * Holds the value for the vertical alignment.
   *
   * Valid values:
   *  - 0: Aligns the element to the top.
   *  - 2: Aligns the element to the bottom.
   */
  var verticalAlignment = 2

  var mainKeymap = defaultKeymapForOS().value
  var mainKeymapLabel: String = defaultKeymapForOS().defaultLabel

  var showAlternativeKeymap = false
  var alternativeKeymap: String = defaultKeymapForOS().getAlternativeKind().value
  var alternativeKeymapLabel: String = defaultKeymapForOS().getAlternativeKind().defaultLabel

  var deltaX: Int? = null
  var deltaY: Int? = null
}

internal fun PresentationAssistantState.resetDelta() {
  deltaX = null
  deltaY = null
}

internal val PresentationAssistantState.alignmentIfNoDelta: PresentationAssistantPopupAlignment? get() =
  if (deltaX == null || deltaY == null) PresentationAssistantPopupAlignment.from(horizontalAlignment, verticalAlignment)
  else null

internal fun PresentationAssistantState.mainKeymapKind() = KeymapKind.from(mainKeymap)
internal fun PresentationAssistantState.alternativeKeymapKind() = alternativeKeymap.takeIf { showAlternativeKeymap }?.let { KeymapKind.from(it) }

@State(name = "PresentationAssistantIJ", storages = [Storage("presentation-assistant-ij.xml")])
class PresentationAssistant : PersistentStateComponent<PresentationAssistantState>, Disposable {
  internal val configuration = PresentationAssistantState()
  private var warningAboutMacKeymapWasShown = false
  private var presenter: ShortcutPresenter? = null

  override fun getState() = configuration
  override fun loadState(p: PresentationAssistantState) {
    XmlSerializerUtil.copyBean(p, configuration)
  }

  fun initialize() {
    if (configuration.showActionDescriptions && presenter == null) {
      presenter = ShortcutPresenter()
    }
  }

  override fun dispose() {
    presenter?.disable()
  }

  fun updatePresenter(project: Project? = null, showInitialAction: Boolean = false) {
    val isEnabled = configuration.showActionDescriptions
    if (isEnabled && presenter == null) {
      presenter = ShortcutPresenter().apply {
        if (showInitialAction) {
          showActionInfo(ShortcutPresenter.ActionData(TogglePresentationAssistantAction.ID, project, TogglePresentationAssistantAction.name))
        }
      }
    }
    else if (presenter != null) {
      if (!isEnabled) {
        presenter?.disable()
        presenter = null
      }
      else {
        presenter?.refreshPresentedPopupIfNeeded()
      }
    }
  }

  internal fun checkIfMacKeymapIsAvailable() {
    val alternativeKeymap = configuration.alternativeKeymapKind()
    if (warningAboutMacKeymapWasShown || defaultKeymapForOS() == KeymapKind.MAC || alternativeKeymap == null) {
      return
    }
    if (alternativeKeymap != KeymapKind.MAC || alternativeKeymap.keymap != null) {
      return
    }

    val pluginId = PluginId.getId("com.intellij.plugins.macoskeymap")
    val plugin = PluginManagerCore.getPlugin(pluginId)
    if (plugin != null && plugin.isEnabled) return

    warningAboutMacKeymapWasShown = true
    showInstallMacKeymapPluginNotification(pluginId)
  }

  companion object {
    val INSTANCE get() = service<PresentationAssistant>()
  }
}

class PresentationAssistantListenerRegistrar : AppLifecycleListener, DynamicPluginListener {
  override fun appFrameCreated(commandLineArgs: MutableList<String>) {
    PresentationAssistant.INSTANCE.initialize()
  }
}
