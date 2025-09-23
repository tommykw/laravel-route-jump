package com.github.tommykw.laravelroutejump

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

class LaravelRouteJumpAction : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val url = Messages.showInputDialog(
            project,
            "Jump to URL:",
            "Laravel Route Jump",
            Messages.getQuestionIcon()
        )
        
        if (!url.isNullOrEmpty()) {
            findAndJumpToController(project, url.trim())
        }
    }
    
    private fun findAndJumpToController(project: Project, url: String) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Searching for route...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val settings = LaravelRouteJumpSettings.getInstance(project)
                    val artisanCommand = settings.artisanCommand
                    
                    if (artisanCommand.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            showConfigurationRequiredDialog(project)
                        }
                        return
                    }
                    
                    indicator.text = "Running artisan route:list..."
                    
                    val processBuilder = ProcessBuilder()
                    processBuilder.directory(java.io.File(project.basePath ?: ""))
                    processBuilder.command(artisanCommand.split(" ") + listOf("route:list", "--json"))
                    
                    val process = processBuilder.start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = reader.readText()
                    
                    val exitCode = process.waitFor()
                    
                    if (exitCode != 0) {
                        ApplicationManager.getApplication().invokeLater {
                            showCommandFailedDialog(project)
                        }
                        return
                    }
                    
                    indicator.text = "Parsing routes..."

                    val controllerAction = findMatchingRoute(output, url)
                    
                    if (controllerAction != null) {
                        ApplicationManager.getApplication().invokeLater {
                            jumpToControllerMethod(project, controllerAction)
                        }
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(
                                project,
                                "No matching route found for: $url",
                                "Route Not Found"
                            )
                        }
                    }
                    
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Error occurred: ${e.message}",
                            "Error"
                        )
                    }
                }
            }
        })
    }
    
    private fun findMatchingRoute(jsonOutput: String, url: String): String? {
        val path = extractPathFromUrl(url)
        val normalizedUrl = path.trim().removePrefix("/").removeSuffix("/")
        val routeRegex = Regex("\\{[^}]*\"uri\"\\s*:\\s*\"([^\"]+)\"[^}]*\"action\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}")
        val matches = routeRegex.findAll(jsonOutput)
        
        for (match in matches) {
            val routeUri = match.groupValues[1]
            val action = match.groupValues[2]
            
            val normalizedRouteUri = routeUri
                .replace("\\/", "/")  // Unescape forward slashes
                .removePrefix("/")
                .removeSuffix("/")
            
            if (normalizedUrl == normalizedRouteUri) {
                return action
            }
            
            if (normalizedRouteUri.contains("{")) {
                val pattern = normalizedRouteUri
                    .replace(Regex("\\{[^}]+\\?\\}"), "(/[^/]+)?")  // Replace {param?} with optional group including slash
                    .replace(Regex("\\{[^}]+\\}"), "[^/]+")  // Replace {param} with [^/]+
                
                if (normalizedUrl.matches(Regex("^$pattern$"))) {
                    return action
                }
            }
        }
        
        return null
    }
    
    private fun extractPathFromUrl(input: String): String {
        val trimmed = input.trim()
        
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val uri = java.net.URI(trimmed)
                return uri.path ?: "/"
            } catch (e: Exception) {
                val pathStart = trimmed.indexOf('/', 8) // Skip protocol part
                return if (pathStart > 0) trimmed.substring(pathStart) else "/"
            }
        }
        
        return trimmed
    }
    
    private fun jumpToControllerMethod(project: Project, controllerAction: String) {
        val parts = controllerAction.split("@")
        if (parts.size != 2) {
            Messages.showErrorDialog(
                project,
                "Invalid controller action format: $controllerAction",
                "Parse Error"
            )
            return
        }
        
        val controllerName = parts[0].substringAfterLast("\\")
        val methodName = parts[1]
        
        val controllerFiles = FilenameIndex.getVirtualFilesByName(
            "$controllerName.php",
            GlobalSearchScope.projectScope(project)
        ).mapNotNull { PsiManager.getInstance(project).findFile(it) }
        
        if (controllerFiles.isEmpty()) {
            Messages.showErrorDialog(
                project,
                "Controller file not found: $controllerName.php",
                "File Not Found"
            )
            return
        }
        
        val controllerFile = controllerFiles[0]
        val psiFile = controllerFile
        
        val fileEditorManager = FileEditorManager.getInstance(project)
        val editors = fileEditorManager.openFile(psiFile.virtualFile, true)
        
        val methodOffset = findMethodInFile(psiFile, methodName)
        
        if (methodOffset != null && editors.isNotEmpty()) {
            val editor = editors[0]
            if (editor is com.intellij.openapi.fileEditor.TextEditor) {
                val textEditor = editor.editor
                ApplicationManager.getApplication().invokeLater {
                    CommandProcessor.getInstance().executeCommand(project, {
                        textEditor.caretModel.moveToOffset(methodOffset)
                        textEditor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                    }, "Navigate to Method", null)
                }
            }
        } else {
            Messages.showInfoMessage(
                project,
                "Method '$methodName' not found in $controllerName",
                "Method Not Found"
            )
        }
    }
    
    private fun findMethodInFile(psiFile: com.intellij.psi.PsiFile, methodName: String): Int? {
        val text = psiFile.text
        val pattern = Pattern.compile("function\\s+($methodName)\\s*\\(")
        val matcher = pattern.matcher(text)
        
        if (matcher.find()) {
            return matcher.start(1)
        }
        
        return null
    }
    
    private fun showConfigurationRequiredDialog(project: Project) {
        val result = Messages.showYesNoDialog(
            project,
            "Artisan command is not configured. Would you like to open settings to configure it?",
            "Configuration Required",
            "Open Settings",
            "Cancel",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LaravelRouteJumpConfigurable::class.java)
        }
    }
    
    private fun showCommandFailedDialog(project: Project) {
        val result = Messages.showYesNoDialog(
            project,
            "Failed to execute artisan command. Would you like to check your configuration?",
            "Command Failed",
            "Open Settings",
            "Cancel",
            Messages.getErrorIcon()
        )
        
        if (result == Messages.YES) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LaravelRouteJumpConfigurable::class.java)
        }
    }
    
    // Test helper methods
    internal fun extractPathFromUrlForTest(input: String): String {
        return extractPathFromUrl(input)
    }
    
    internal fun findMatchingRouteForTest(jsonOutput: String, url: String): String? {
        return findMatchingRoute(jsonOutput, url)
    }
}