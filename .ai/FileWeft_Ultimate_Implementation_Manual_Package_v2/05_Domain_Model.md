
# Domain Model

FileObject:

physical file.


FileAsset:

business association.


Document:

owns:

- lifecycle
- versions
- publication state


Never put:

- storage SDK
- HTTP
- database
inside domain.
