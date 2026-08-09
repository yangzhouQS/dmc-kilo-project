package com.dmc.wiki

import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import java.awt.Component
import javax.swing.JList
import javax.swing.ListCellRenderer

class WikiFileCellRenderer : ListCellRenderer<String> {
    override fun getListCellRendererComponent(
        list: JList<out String>?,
        value: String?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ): Component {
        val comp = SimpleColoredComponent()
        comp.icon = AllIcons.FileTypes.Text
        val text = value ?: ""
        if (text.startsWith("（")) {
            comp.append(text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else {
            comp.append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        return comp
    }
}
