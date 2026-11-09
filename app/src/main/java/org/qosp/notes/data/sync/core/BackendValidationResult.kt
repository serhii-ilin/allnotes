package org.qosp.notes.data.sync.core

sealed interface BackendValidationResult {
    data object Success : BackendValidationResult
    data object InvalidConfig : BackendValidationResult
}
