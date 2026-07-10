
# Workflow Event Outbox

Lifecycle and workflow are separated.

Lifecycle:
state transition.

Workflow:
human approval.


Production external calls use:

Business Transaction

+

fw_outbox_event

+

Worker

+

Connector


Never call external API inside transaction.
