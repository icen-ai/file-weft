
# Spring Boot Auto Configuration


Separate:

fileweft-spring-boot2-starter

fileweft-spring-boot3-starter


Required:


```kotlin
@AutoConfiguration
class FileWeftAutoConfiguration
```


Use:

ConditionalOnMissingBean


Loading order:

1 user bean
2 plugin bean
3 default bean


Never use aggressive component scanning.
