package dev.gitdashboard

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/** Coroutine scope tied to the project's lifetime; cancelled when the project closes or the plugin unloads. */
@Service(Service.Level.PROJECT)
class GitDashboardScope(val cs: CoroutineScope)
