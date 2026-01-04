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
import com.google.common.annotations.VisibleForTesting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Route(
    val domain: String? = null,
    val uri: String,
    val action: String,
    val method: JsonElement? = null,
)

data class RouteMatch(
    val action: String,
    val methods: List<String>,
)

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

                    val projectDir = java.io.File(project.basePath ?: "")
                    if (!projectDir.exists()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "Project directory not found: ${projectDir.absolutePath}",
                                "Directory Error"
                            )
                        }
                        return
                    }

                    val processBuilder = ProcessBuilder()
                    processBuilder.directory(projectDir)

                    // Use shell to execute command so that PATH is used to find executables
                    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                    val shellCommand = if (isWindows) {
                        listOf("cmd.exe", "/c", "$artisanCommand route:list --json")
                    } else {
                        listOf("/bin/sh", "-c", "$artisanCommand route:list --json")
                    }
                    processBuilder.command(shellCommand)

                    val process = processBuilder.start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                    val output = reader.readText()
                    val errorOutput = errorReader.readText()

                    val exitCode = process.waitFor()

                    if (exitCode != 0) {
                        ApplicationManager.getApplication().invokeLater {
                            val result = Messages.showYesNoDialog(
                                project,
                                """
                                    Command: $artisanCommand route:list --json
                                    Working directory: ${projectDir.absolutePath}
                                    Error output: $errorOutput

                                    The artisan command path might be incorrect. Please check your configuration.
                                """.trimIndent(),
                                "Command failed with exit code $exitCode",
                                "Open Settings",
                                "Cancel",
                                Messages.getErrorIcon(),
                            )

                            if (result == Messages.YES) {
                                ShowSettingsUtil.getInstance().showSettingsDialog(project, LaravelRouteJumpConfigurable::class.java)
                            }
                        }
                        return
                    }
                    
                    indicator.text = "Parsing routes..."

                    val routeMatches = findMatchingRoutes(output, url)

                    if (routeMatches.isNotEmpty()) {
                        val methodToAction = linkedMapOf<String, String>()
                        for (match in routeMatches.filter { isNavigableAction(it.action) }) {
                            for (method in match.methods) {
                                methodToAction.putIfAbsent(method, match.action)
                            }
                        }

                        ApplicationManager.getApplication().invokeLater {
                            if (methodToAction.isEmpty()) {
                                Messages.showInfoMessage(
                                    project,
                                    "Matching route uses a Closure and cannot be navigated:\n$url",
                                    "Route Not Navigable"
                                )
                            } else if (methodToAction.size <= 1) {
                                val controllerAction = methodToAction.values.first()
                                jumpToControllerMethod(project, controllerAction)
                            } else {
                                showMethodSelectionDialog(project, url, methodToAction)
                            }
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
                        if (e.message?.contains("Cannot run program \"php\"") == true ||
                            e.message?.contains("No such file or directory") == true) {
                            showPhpNotFoundDialog(project)
                        } else {
                            Messages.showErrorDialog(
                                project,
                                "Error occurred: ${e.message}",
                                "Error"
                            )
                        }
                    }
                }
            }
        })
    }
    
    private fun findMatchingRoutes(jsonOutput: String, url: String): List<RouteMatch> {
        val path = extractPathFromUrl(url)
        val normalizedUrl = path.trim().removePrefix("/").removeSuffix("/")

        val json = Json { ignoreUnknownKeys = true }
        val routes = try {
            json.decodeFromString<List<Route>>(jsonOutput)
        } catch (e: Exception) {
            return emptyList()
        }

        val matches = mutableListOf<RouteMatch>()
        for (route in routes) {
            val routeUri = route.uri
            val action = route.action
            val domain = route.domain
            val routeMethods = normalizeMethods(route.method)

            // Extract path from route URI (handles subdomain routes)
            val routePath = extractPathFromUrl(routeUri)
            val normalizedRouteUri = routePath
                .replace("\\/", "/")  // Unescape forward slashes
                .removePrefix("/")
                .removeSuffix("/")

            // Exact match without parameters
            if (normalizedUrl == normalizedRouteUri) {
                matches.add(RouteMatch(action, routeMethods))
                continue
            }

            // Pattern matching for routes with parameters
            if (normalizedRouteUri.contains("{")) {
                try {
                    val pattern = normalizedRouteUri
                        .replace("""\{[^}]+\?\}""".toRegex(), "(/[^/]+)?")  // Replace {param?} with optional group
                        .replace("""\{[^}]+\}""".toRegex(), "[^/]+")  // Replace {param} with [^/]+

                    if (normalizedUrl.matches("^$pattern$".toRegex())) {
                        matches.add(RouteMatch(action, routeMethods))
                        continue
                    }
                } catch (e: Exception) {
                    // Skip routes with invalid regex patterns (e.g., unclosed braces)
                }
            }

            // Handle routes with domain constraints
            // If domain is set (not null or empty), match against full URL including domain
            if (!domain.isNullOrEmpty()) {
                try {
                    // Build full route pattern: domain/uri
                    val fullRoute = "$domain/$routeUri"
                        .replace("\\/", "/")  // Unescape forward slashes
                        .replace("""\{[^}]+\?\}""".toRegex(), "([^/]+)?")  // Replace {param?} with optional group
                        .replace("""\{[^}]+\}""".toRegex(), "[^/.]+")  // Replace {param} with [^/.]+

                    // Try to match with full URL (e.g., "develop.localhost/manage/login")
                    val trimmedUrl = url.trim()
                    val urlWithoutProtocol = trimmedUrl
                        .removePrefix("http://")
                        .removePrefix("https://")
                        .removePrefix("/")
                        .removeSuffix("/")

                    if (urlWithoutProtocol.matches("^$fullRoute$".toRegex())) {
                        matches.add(RouteMatch(action, routeMethods))
                        continue
                    }
                } catch (e: Exception) {
                    // Skip routes with invalid regex patterns
                }
            }

            // Also try matching the full route URI (including subdomain) as a pattern
            // This handles cases where user inputs the full route like {account}.localhost/path
            val fullRoutePattern = routeUri
                .replace("\\/", "/")  // Unescape forward slashes
                .replace("""\{[^}]+\?\}""".toRegex(), "([^/]+)?")  // Replace {param?} with optional group
                .replace("""\{[^}]+\}""".toRegex(), "[^/.]+")  // Replace {param} with [^/.]+

            val normalizedInput = url.trim().removePrefix("/").removeSuffix("/")
            if (normalizedInput.matches("^$fullRoutePattern$".toRegex())) {
                matches.add(RouteMatch(action, routeMethods))
                continue
            }
        }

        return matches
    }

    private fun normalizeMethods(methodsElement: JsonElement?): List<String> {
        val rawMethods = when (methodsElement) {
            is JsonArray -> methodsElement.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { value -> value != "null" } }
            is JsonPrimitive -> listOf(methodsElement.content).filter { it != "null" }
            else -> emptyList()
        }

        val normalized = rawMethods
            .flatMap { it.split("|") }
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) {
            return listOf("GET")
        }
        val withoutHead = normalized.filterNot { it == "HEAD" }
        return if (withoutHead.isEmpty()) normalized else withoutHead
    }

    private fun showMethodSelectionDialog(
        project: Project,
        url: String,
        methodToAction: LinkedHashMap<String, String>,
    ) {
        val methods = methodToAction.keys.toList()
        val options = (methods + "Cancel").toTypedArray()
        val result = Messages.showDialog(
            project,
            "Multiple HTTP methods found for:\n$url\nSelect a method:",
            "Select HTTP Method",
            options,
            0,
            Messages.getQuestionIcon()
        )

        if (result in methods.indices) {
            val selectedMethod = methods[result]
            val controllerAction = methodToAction[selectedMethod] ?: return
            jumpToControllerMethod(project, controllerAction)
        }
    }

    private fun isNavigableAction(action: String): Boolean {
        return action.contains("@")
    }
    
    private fun extractPathFromUrl(input: String): String {
        val trimmed = input.trim()

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val uri = java.net.URI(trimmed)
                return uri.path ?: "/"
            } catch (e: Exception) {
                val protocolEnd = if (trimmed.startsWith("https://")) 8 else 7
                val pathStart = trimmed.indexOf('/', protocolEnd)
                return if (pathStart > 0) trimmed.substring(pathStart) else "/"
            }
        }

        // Handle subdomain patterns like {account}.localhost/path or example.com/path
        val firstSlashIndex = trimmed.indexOf('/')
        if (firstSlashIndex > 0) {
            val beforeSlash = trimmed.substring(0, firstSlashIndex)
            // If it looks like a domain (contains . or {}), extract path
            if (beforeSlash.contains('.') || beforeSlash.contains('{') || beforeSlash.contains('}')) {
                return trimmed.substring(firstSlashIndex)
            }
        }

        // Handle query parameters and anchors for plain paths
        val queryIndex = trimmed.indexOf('?')
        val anchorIndex = trimmed.indexOf('#')
        val endIndex = when {
            queryIndex > 0 && anchorIndex > 0 -> minOf(queryIndex, anchorIndex)
            queryIndex > 0 -> queryIndex
            anchorIndex > 0 -> anchorIndex
            else -> trimmed.length
        }

        return trimmed.substring(0, endIndex)
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
        val fileEditorManager = FileEditorManager.getInstance(project)
        val editors = fileEditorManager.openFile(controllerFile.virtualFile, true)
        
        val methodOffset = findMethodInFile(controllerFile, methodName)
        
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

    private fun showPhpNotFoundDialog(project: Project) {
        val result = Messages.showYesNoDialog(
            project,
            "PHP command not found. Please configure the full path to PHP.\nExample: '/path/to/php artisan'",
            "PHP Not Found",
            "Open Settings",
            "Cancel",
            Messages.getErrorIcon()
        )

        if (result == Messages.YES) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LaravelRouteJumpConfigurable::class.java)
        }
    }
    
    @VisibleForTesting
    internal fun extractPathFromUrlForTest(input: String): String {
        return extractPathFromUrl(input)
    }
    
    @VisibleForTesting
    internal fun findMatchingRouteForTest(jsonOutput: String, url: String): String? {
        return findMatchingRoutes(jsonOutput, url).firstOrNull()?.action
    }

    @VisibleForTesting
    internal fun findMatchingRoutesForTest(jsonOutput: String, url: String): List<RouteMatch> {
        return findMatchingRoutes(jsonOutput, url)
    }
}
