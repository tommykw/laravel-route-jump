package com.github.tommykw.laravelroutejump

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class LaravelRouteJumpConfigurable(private val project: Project) : Configurable {
    
    private lateinit var panel: DialogPanel
    private val settings = LaravelRouteJumpSettings.getInstance(project)
    
    override fun getDisplayName(): String = "Laravel Route Jump"
    
    override fun createComponent(): JComponent {
        panel = panel {
            group("Configuration") {
                row("Artisan Command:") {
                    textField()
                        .bindText(settings::artisanCommand)
                        .comment("Full path to PHP may be required. Examples: '/path/to/php artisan', '/path/to/docker compose exec app php artisan'")
                        .resizableColumn()
                        .align(AlignX.FILL)
                }
            }
        }
        
        return panel
    }
    
    override fun isModified(): Boolean {
        return panel.isModified()
    }
    
    override fun apply() {
        panel.apply()
    }
    
    override fun reset() {
        panel.reset()
    }
}