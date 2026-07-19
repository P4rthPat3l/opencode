package ai.opencode.p4rth.jetbrains.settings

import ai.opencode.p4rth.jetbrains.runtime.OpenCodeApplicationService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Settings UI with plain-language labels for everyday users.
 * Commit model list comes from the OpenCode sidecar (same catalog as the chat picker).
 */
class OpenCodeConfigurable : Configurable {
  private val settings get() = ApplicationManager.getApplication().getService(OpenCodeSettings::class.java)
  private var panel: JPanel? = null

  private val externalRuntimePath = JBTextField().apply {
    emptyText.text = "Leave empty to use the built-in OpenCode (recommended)"
  }
  private val manifestUrl = JBTextField().apply {
    emptyText.text = "Used only when OpenCode needs to download an update"
  }
  private val diagnosticLogging = JBCheckBox("Help troubleshoot problems (writes extra logs)")

  private val commitMessageModel = ComboBox(DefaultComboBoxModel(arrayOf(ModelChoice.DEFAULT))).apply {
    renderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
      ): Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = (value as? ModelChoice)?.label ?: value?.toString().orEmpty()
        return c
      }
    }
    prototypeDisplayValue = ModelChoice(
      value = "opencode/deepseek-v4-flash-free",
      label = "DeepSeek V4 Flash Free — OpenCode Zen",
    )
  }
  private val refreshModelsButton = JButton("Refresh list").apply {
    toolTipText = "Reload the AI models available in OpenCode (same list as chat)"
  }
  private val commitMessagePrompt = JBTextArea(10, 40).apply {
    lineWrap = true
    wrapStyleWord = true
    emptyText.text = "How OpenCode should write your commit messages. Keep {{diff}} so your changes are included."
  }
  private val commitMessageReplaceMode = ComboBox(ReplaceMode.entries.toTypedArray()).apply {
    renderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
      ): Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = (value as? ReplaceMode)?.label ?: value?.toString().orEmpty()
        return c
      }
    }
  }
  private val modelHelp = mutedLabel(
    "Choose which AI model writes commit messages. This is the same list as in the OpenCode chat. " +
      "Pick <b>Use my usual OpenCode model</b> unless you want a specific one for commits.",
  )
  private val modelStatus = mutedLabel(" ").apply {
    border = JBUI.Borders.emptyTop(2)
  }
  private val promptHelp = mutedLabel(
    "Optional. Tells OpenCode how to phrase messages. Include <code>{{diff}}</code> where your staged changes should go.",
  )
  private val replaceHelp = mutedLabel(
    "What to do if the commit box already has text when you generate a message.",
  )

  override fun getDisplayName() = "P4rth OpenCode"

  override fun createComponent(): JComponent {
    externalRuntimePath.text = settings.state.externalRuntimePath
    manifestUrl.text = settings.state.manifestUrl
    diagnosticLogging.isSelected = settings.state.diagnosticLogging
    commitMessagePrompt.text = settings.state.commitMessagePrompt.ifBlank { OpenCodeSettings.DEFAULT_COMMIT_MESSAGE_PROMPT }
    commitMessageReplaceMode.selectedItem = ReplaceMode.fromStored(settings.state.commitMessageReplaceMode)

    setModelChoices(listOf(ModelChoice.DEFAULT), settings.state.commitMessageModel)
    refreshModelsButton.addActionListener { loadModelsAsync(userInitiated = true) }

    val modelRow = JPanel(BorderLayout(8, 0)).apply {
      add(commitMessageModel, BorderLayout.CENTER)
      add(refreshModelsButton, BorderLayout.EAST)
    }

    panel = FormBuilder.createFormBuilder()
      .addComponent(sectionTitle("Commit messages"))
      .addComponent(
        mutedLabel("Used when you click <b>Generate Commit Message with OpenCode</b> in the Commit window."),
      )
      .addVerticalGap(8)
      .addLabeledComponent("AI model", modelRow)
      .addComponent(modelHelp)
      .addComponent(modelStatus)
      .addVerticalGap(8)
      .addLabeledComponent("Writing style", JBScrollPane(commitMessagePrompt), 1, false)
      .addComponent(promptHelp)
      .addVerticalGap(8)
      .addLabeledComponent("If the commit box already has text", commitMessageReplaceMode)
      .addComponent(replaceHelp)
      .addVerticalGap(16)
      .addComponent(sectionTitle("Advanced"))
      .addComponent(
        mutedLabel("Most people can leave these alone. Only change them if support asks you to."),
      )
      .addVerticalGap(8)
      .addLabeledComponent("Custom OpenCode path", externalRuntimePath)
      .addComponent(
        mutedLabel("Optional path to an OpenCode program on your computer. Leave blank to use the built-in one."),
      )
      .addVerticalGap(6)
      .addLabeledComponent("Update source", manifestUrl)
      .addComponent(
        mutedLabel("Where the plugin checks for OpenCode runtime updates. Do not change unless you know you need to."),
      )
      .addVerticalGap(6)
      .addComponent(diagnosticLogging)
      .addComponentFillVertically(JPanel(), 0)
      .panel

    loadModelsAsync(userInitiated = false)
    return panel!!
  }

  override fun isModified(): Boolean {
    val selected = (commitMessageModel.selectedItem as? ModelChoice)?.value.orEmpty()
    val replace = (commitMessageReplaceMode.selectedItem as? ReplaceMode)?.stored
      ?: settings.state.commitMessageReplaceMode
    return externalRuntimePath.text != settings.state.externalRuntimePath ||
      manifestUrl.text != settings.state.manifestUrl ||
      diagnosticLogging.isSelected != settings.state.diagnosticLogging ||
      selected != settings.state.commitMessageModel ||
      commitMessagePrompt.text != settings.state.commitMessagePrompt ||
      replace != settings.state.commitMessageReplaceMode
  }

  override fun apply() {
    settings.state.externalRuntimePath = externalRuntimePath.text.trim()
    settings.state.manifestUrl = manifestUrl.text.trim()
    settings.state.runtimeMode = if (settings.state.externalRuntimePath.isBlank()) "managed" else "externalFork"
    settings.state.diagnosticLogging = diagnosticLogging.isSelected
    settings.state.commitMessageModel = (commitMessageModel.selectedItem as? ModelChoice)?.value.orEmpty().trim()
    settings.state.commitMessagePrompt = commitMessagePrompt.text.ifBlank { OpenCodeSettings.DEFAULT_COMMIT_MESSAGE_PROMPT }
    settings.state.commitMessageReplaceMode =
      (commitMessageReplaceMode.selectedItem as? ReplaceMode)?.stored ?: ReplaceMode.ALWAYS_REPLACE.stored
  }

  override fun reset() {
    externalRuntimePath.text = settings.state.externalRuntimePath
    manifestUrl.text = settings.state.manifestUrl
    diagnosticLogging.isSelected = settings.state.diagnosticLogging
    commitMessagePrompt.text = settings.state.commitMessagePrompt.ifBlank { OpenCodeSettings.DEFAULT_COMMIT_MESSAGE_PROMPT }
    commitMessageReplaceMode.selectedItem = ReplaceMode.fromStored(settings.state.commitMessageReplaceMode)
    val keep = settings.state.commitMessageModel
    val model = commitMessageModel.model as DefaultComboBoxModel<ModelChoice>
    val match = (0 until model.size).map { model.getElementAt(it) }.firstOrNull { it.value == keep }
    if (match != null) {
      commitMessageModel.selectedItem = match
    } else {
      setModelChoices(listOf(ModelChoice.DEFAULT), keep)
    }
  }

  override fun disposeUIResources() {
    panel = null
  }

  private fun loadModelsAsync(userInitiated: Boolean) {
    val project = ProjectManager.getInstance().openProjects.firstOrNull()
    if (project == null) {
      modelStatus.text = "Open a project first, then click Refresh list."
      return
    }

    modelStatus.text = "Loading available models…"
    refreshModelsButton.isEnabled = false
    val preferred = (commitMessageModel.selectedItem as? ModelChoice)?.value
      ?: settings.state.commitMessageModel

    ApplicationManager.getApplication().executeOnPooledThread {
      val result = runCatching {
        ApplicationManager.getApplication()
          .getService(OpenCodeApplicationService::class.java)
          .listModels(project)
      }
      SwingUtilities.invokeLater {
        refreshModelsButton.isEnabled = true
        result.fold(
          onSuccess = { options ->
            val choices = buildList {
              add(ModelChoice.DEFAULT)
              options.forEach { add(ModelChoice(it.value, it.label)) }
            }
            setModelChoices(choices, preferred)
            modelStatus.text = if (options.isEmpty()) {
              "No models found. Open the OpenCode chat, sign in if needed, then click Refresh list."
            } else {
              "${options.size} models available — same as in OpenCode chat."
            }
          },
          onFailure = { error ->
            val short = error.message?.take(120) ?: "something went wrong"
            modelStatus.text = "Couldn’t load models ($short). Try opening OpenCode chat, then Refresh list."
            if (preferred.isNotBlank() && (commitMessageModel.selectedItem as? ModelChoice)?.value != preferred) {
              setModelChoices(listOf(ModelChoice.DEFAULT, ModelChoice.custom(preferred)), preferred)
            }
          },
        )
      }
    }
  }

  private fun setModelChoices(choices: List<ModelChoice>, preferredValue: String) {
    val model = DefaultComboBoxModel<ModelChoice>()
    val seen = HashSet<String>()
    for (choice in choices) {
      if (seen.add(choice.value)) model.addElement(choice)
    }
    if (preferredValue.isNotBlank() && preferredValue !in seen) {
      model.addElement(ModelChoice.custom(preferredValue))
    }
    commitMessageModel.model = model
    val match = (0 until model.size).map { model.getElementAt(it) }.firstOrNull { it.value == preferredValue }
    commitMessageModel.selectedItem = match ?: model.getElementAt(0)
  }

  private fun sectionTitle(text: String): JBLabel =
    JBLabel(text).apply {
      font = font.deriveFont(Font.BOLD)
      border = JBUI.Borders.emptyTop(4)
    }

  private fun mutedLabel(html: String): JBLabel =
    JBLabel("<html><body style='width:420px'>$html</body></html>").apply {
      border = JBUI.Borders.emptyTop(4)
    }

  /** Combo entry: empty [value] means use OpenCode’s usual model. */
  data class ModelChoice(val value: String, val label: String) {
    override fun toString(): String = label

    companion object {
      val DEFAULT = ModelChoice("", "Use my usual OpenCode model")
      fun custom(value: String) = ModelChoice(value, "Saved choice ($value)")
    }
  }

  enum class ReplaceMode(val stored: String, val label: String) {
    ALWAYS_REPLACE("alwaysReplace", "Replace it with the new message"),
    REPLACE_IF_EMPTY("replaceIfEmpty", "Only fill in if the box is empty"),
    APPEND("append", "Add the new message below the existing text"),
    ;

    companion object {
      fun fromStored(raw: String): ReplaceMode =
        entries.firstOrNull { it.stored == raw } ?: ALWAYS_REPLACE
    }
  }
}
