package com.buddystuddy.backend.config

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AdminPageController {
    @GetMapping("/admin", "/admin/")
    fun admin(): String = "admin/index"
}
