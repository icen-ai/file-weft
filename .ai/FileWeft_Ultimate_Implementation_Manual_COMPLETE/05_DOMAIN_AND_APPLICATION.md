
# Domain and Application


FileObject:
physical file.


FileAsset:
business attachment.


Document:
owns:
- lifecycle
- version
- publication


Application services:

UploadApplicationService

DocumentCommandService

PublishService

OfflineService

DoctorService


Application orchestrates.
Domain decides rules.

