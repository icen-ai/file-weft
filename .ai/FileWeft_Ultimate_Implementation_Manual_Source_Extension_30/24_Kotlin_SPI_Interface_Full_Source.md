
# Full SPI Interface Contract


## StorageAdapter

Methods:

- upload
- download
- delete
- exists
- accessUrl


## UserRealmProvider

Methods:

- currentUser
- findUser


## AuthorizationProvider

Methods:

- authorize


## TenantProvider

Methods:

- currentTenant


## FileConnector

Methods:

- sync
- remove
- health


All SPI interfaces require compatibility.
