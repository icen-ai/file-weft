
# Core Kotlin Source Rules

Core contains:

- Result
- ErrorCode
- DomainEvent
- Identifier
- Context


Example:

```kotlin
interface DomainEvent {
    val id:String
    val timestamp:Long
}
```


No Spring.
No ORM.
No SDK.
