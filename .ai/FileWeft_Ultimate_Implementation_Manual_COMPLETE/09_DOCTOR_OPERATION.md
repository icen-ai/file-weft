
# Doctor System

> **Superseded Agent item:** Agent diagnosis below is retained as historical
> design only. `0.0.2` does not register an Agent checker by default. Agent may
> be reassessed only after `1.0.0` is released, with no promised delivery
> version.


Endpoint:

GET /document/{id}/doctor


Checks:

Storage
Permission
Lifecycle
Workflow
Connector
Agent (historical only; excluded from the default checker inventory)


Report:

status
reason
evidence
repair suggestion
