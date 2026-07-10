package com.fileweft.dev.api.web

import com.fileweft.dev.api.catalog.DevCatalogFolderView
import com.fileweft.dev.api.catalog.DevCatalogQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/catalog")
class DevCatalogController(
    private val catalog: DevCatalogQueryService,
) {
    @GetMapping("/folders")
    fun folders(): List<DevCatalogFolderView> = catalog.folders()
}
