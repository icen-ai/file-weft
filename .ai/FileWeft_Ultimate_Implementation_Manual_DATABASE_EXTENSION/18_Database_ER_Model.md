
# Database ER Model


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


## AI


fw_document

        |

fw_agent_task

        |

fw_agent_result
