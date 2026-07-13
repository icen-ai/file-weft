
# Database ER Model

> **Agent schema boundary:** the AI tables shown below are retained historical
> V012/V026 database shape only. `0.0.2` does not expose Agent product
> capability. Do not remove or repurpose these compatibility tables; redesign
> may be reassessed only after `1.0.0` is released, with no promised version.


## Core Relationship


fw_file_object

        1

        |

        N


fw_asset

        |

        1


fw_document


        |

        N


fw_document_version



## Workflow


fw_document

        |

fw_workflow_instance

        |

fw_workflow_task



## Synchronization


fw_document

        |

fw_sync_record

        |

connector


## AI (historical compatibility schema)


fw_document

        |

fw_agent_task

        |

fw_agent_result
