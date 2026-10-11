package com.ibrahimdans.i18n.plugin.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * The name a review is signed with, as the person typed it — `Alice Martin <alice@example.com>`.
 *
 * Application level, never in the project: it is who sits at this IDE, not a setting of the team,
 * and must never end up versioned. Blank, [ReviewerIdentity] falls back to Git, then to the OS account.
 */
@Service(Service.Level.APP)
@State(name = "i18nReviewer", storages = [Storage("i18nSupportPlusReviewer.xml")])
class ReviewerSettings : PersistentStateComponent<ReviewerSettings.State> {

    data class State(var name: String = "")

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var name: String
        get() = state.name
        set(value) {
            state = state.copy(name = value.trim())
        }

    companion object {
        fun getInstance(): ReviewerSettings =
            ApplicationManager.getApplication().getService(ReviewerSettings::class.java)
    }
}
