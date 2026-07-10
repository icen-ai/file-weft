
# Application Service Specification


Application layer orchestrates use cases.


## UploadApplicationService


Flow:

1. receive command
2. authorize
3. create upload session
4. call StorageAdapter
5. create FileObject
6. publish event


## PublishApplicationService


Flow:

1. authorize
2. load Document
3. Lifecycle transition
4. save aggregate
5. create OutboxEvent


Never call external connector directly.
