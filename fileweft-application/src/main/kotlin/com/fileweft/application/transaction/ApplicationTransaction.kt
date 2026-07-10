package com.fileweft.application.transaction

interface ApplicationTransaction {
    fun <T> execute(action: () -> T): T
}
