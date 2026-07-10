
# Kotlin Full Source Templates

This chapter defines source templates.

## Command

Every write operation uses command objects.

Example:

```kotlin
data class PublishDocumentCommand(
    val documentId:String,
    val operatorId:String
)
```


## Service Pattern

Application service:

- validate input
- authorize
- call domain
- persist
- publish event


No business rule in controller.
