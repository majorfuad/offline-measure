package com.fuad.offlinemeasure.ar

import android.content.Context
import com.google.ar.core.Config
import com.google.ar.core.Session

class ArSessionManager(
    private val context: Context
) {

    var session: Session? = null
        private set

    fun createSession(): Session {

        session?.let {
            return it
        }

        val newSession = Session(context)

        val config = Config(newSession).apply {

            planeFindingMode =
                Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

            focusMode =
                Config.FocusMode.AUTO
        }

        newSession.configure(config)

        session = newSession

        return newSession
    }

    fun resume() {
        session?.resume()
    }

    fun pause() {
        session?.pause()
    }

    fun destroy() {
        session?.close()
        session = null
    }
}