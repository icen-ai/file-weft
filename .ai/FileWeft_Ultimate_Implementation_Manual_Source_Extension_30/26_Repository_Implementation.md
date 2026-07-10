
# Repository Implementation


Domain repository:


DocumentRepository


Implementations:

- jdbc
- jpa
- mybatis
- jimmer


Repository responsibilities:

- persistence mapping
- tenant filtering
- optimistic locking


Repository must not contain business workflow.
