
# Kotlin Domain Source Code Specification

## Document Aggregate

Package:

ai.icen.fw.domain.document


Responsibilities:

- lifecycle transition
- version relation
- publication state
- invariant protection


Forbidden:

- storage calls
- HTTP calls
- repository calls


Example:


```kotlin
class Document(
    val id:String,
    private var state:LifecycleState
){

    fun transition(
        command:LifecycleCommand
    ){
        // validate invariant
        // change state
    }

}
```


Domain owns rules, not persistence.
