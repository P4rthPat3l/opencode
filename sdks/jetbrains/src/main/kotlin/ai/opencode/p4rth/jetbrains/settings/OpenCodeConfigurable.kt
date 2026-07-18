package ai.opencode.p4rth.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class OpenCodeConfigurable : Configurable {
  private val settings get() = ApplicationManager.getApplication().getService(OpenCodeSettings::class.java)
  private var panel: JPanel? = null
  private val externalRuntimePath = JBTextField()
  private val manifestUrl = JBTextField()
  private val diagnosticLogging = JBCheckBox("Enable diagnostic logging")

  override fun getDisplayName() = "P4rth OpenCode"

  override fun createComponent(): JComponent {
    externalRuntimePath.text = settings.state.externalRuntimePath
    manifestUrl.text = settings.state.manifestUrl
    diagnosticLogging.isSelected = settings.state.diagnosticLogging
    panel = FormBuilder.createFormBuilder()
      .addLabeledComponent("External fork runtime", externalRuntimePath)
      .addLabeledComponent("Managed runtime manifest", manifestUrl)
      .addComponent(diagnosticLogging)
      .addComponentFillVertically(JPanel(), 0)
      .panel
    return panel!!
  }

  override fun isModified() = externalRuntimePath.text != settings.state.externalRuntimePath ||
    manifestUrl.text != settings.state.manifestUrl ||
    diagnosticLogging.isSelected != settings.state.diagnosticLogging

  override fun apply() {
    settings.state.externalRuntimePath = externalRuntimePath.text.trim()
    settings.state.manifestUrl = manifestUrl.text.trim()
    settings.state.runtimeMode = if (settings.state.externalRuntimePath.isBlank()) "managed" else "externalFork"
    settings.state.diagnosticLogging = diagnosticLogging.isSelected
  }

  override fun reset() {
    externalRuntimePath.text = settings.state.externalRuntimePath
    manifestUrl.text = settings.state.manifestUrl
    diagnosticLogging.isSelected = settings.state.diagnosticLogging
  }

  override fun disposeUIResources() {
    panel = null
  }
}
