package ai.icen.fw.application.transaction

interface ApplicationTransaction {
    fun <T> execute(action: () -> T): T
}
