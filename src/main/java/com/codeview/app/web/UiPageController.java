package com.codeview.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the developer-facing HTML pages. All data on these pages loads via
 * client-side fetch calls to /ui/api/* (see UiDataController) — the
 * templates themselves render an empty shell server-side.
 */
@Controller
public class UiPageController {

    @GetMapping("/ui")
    public String index() {
        return "index";
    }

    @GetMapping("/ui/tree")
    public String tree() {
        return "tree";
    }

    @GetMapping("/ui/flow")
    public String flow() {
        return "flow";
    }

    @GetMapping("/ui/upload")
    public String upload() {
        return "upload";
    }
}
