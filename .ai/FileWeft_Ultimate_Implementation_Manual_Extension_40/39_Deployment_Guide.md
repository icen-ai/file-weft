
# Deployment Guide


Production topology:


Application

|

FileWeft Runtime

|

Database

|

Storage

|

Connector Worker


Recommended:

separate async worker for:

- sync
- AI
- doctor
