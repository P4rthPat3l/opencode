package ai.opencode.p4rth.jetbrains.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object OpenCodeIcons {
  /** 16×16 official mark for toolbar / commit UI actions (not the 512px brand asset). */
  val Action: Icon = IconLoader.getIcon("/icons/opencodeAction.svg", OpenCodeIcons::class.java)

  /** Tool window strip icon (also 16×16). */
  val ToolWindow: Icon = IconLoader.getIcon("/icons/opencodeToolWindow.svg", OpenCodeIcons::class.java)
}
