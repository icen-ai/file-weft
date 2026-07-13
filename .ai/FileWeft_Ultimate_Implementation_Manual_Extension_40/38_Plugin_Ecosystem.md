
# Plugin Ecosystem

> **Superseded Agent extension:** the `new agent` item below is historical. Do
> not add or advertise Agent plugins for `0.0.2`. The retained Agent SPI exists
> only for compatibility until a redesign is reassessed after `1.0.0`, with no
> promised delivery version.


Plugin can add:


- new storage
- new connector
- new workflow
- new agent (historical/superseded; compatibility SPI only)


Plugin cannot modify core.


Plugin discovery:

Spring Bean

or

Java ServiceLoader
