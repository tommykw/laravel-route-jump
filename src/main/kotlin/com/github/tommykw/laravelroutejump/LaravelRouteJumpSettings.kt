package com.github.tommykw.laravelroutejump

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@State(
    name = "LaravelRouteJumpSettings",
    storages = [Storage("laravelRouteJump.xml")]
)
@Service(Service.Level.PROJECT)
class LaravelRouteJumpSettings : PersistentStateComponent<LaravelRouteJumpSettings.State> {
    
    data class State(
        var artisanCommand: String = "php artisan"
    )
    
    private var myState = State()
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        myState = state
    }
    
    var artisanCommand: String
        get() = myState.artisanCommand
        set(value) {
            myState.artisanCommand = value
        }
    
    companion object {
        fun getInstance(project: Project): LaravelRouteJumpSettings {
            return project.service<LaravelRouteJumpSettings>()
        }
    }
}