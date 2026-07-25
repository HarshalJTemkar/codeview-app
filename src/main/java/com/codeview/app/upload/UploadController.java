package com.codeview.app.upload;

import com.codeview.app.model.IndexRunResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Upload endpoints backing the browser upload page (/ui/upload). No auth,
 * consistent with the rest of this application. Both endpoints reject
 * non-.java content silently rather than failing the whole upload — see
 * UploadService for what's actually indexed.
 */
@RestController
@RequestMapping("/mcp/code/upload")
@Tag(name = "Upload", description = "Browser/client upload of a .zip or a folder, indexed the same way as any other source")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Operation(summary = "Upload and index a .zip archive")
    @PostMapping(value = "/zip", consumes = "multipart/form-data")
    public IndexRunResult uploadZip(@RequestParam("file") MultipartFile file) throws Exception {
        return uploadService.indexUploadedZip(file);
    }

    @Operation(summary = "Upload and index a folder",
            description = "Send one part per file under the field name 'files', each with its relative path as the filename (see static/js/upload.js).")
    @PostMapping(value = "/folder", consumes = "multipart/form-data")
    public IndexRunResult uploadFolder(@RequestParam("files") MultipartFile[] files) throws Exception {
        return uploadService.indexUploadedFolder(files);
    }

    /**
     * The folder-upload path already filters to .java files client-side
     * (upload.js) before anything is sent, but a .zip is a single opaque
     * file the client can't pre-filter — if someone zips up build output
     * (target/, .git/) along with source, this is the error they'll hit.
     * Returning a clear, actionable message here instead of letting the
     * raw exception surface as an unhandled 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "Upload exceeds the configured size limit.",
                "hint", "For a .zip upload: exclude build output (target/, build/) and .git/ before "
                        + "zipping — only .java source is indexed anyway. For a folder upload, this "
                        + "shouldn't normally happen since non-.java files are filtered out before "
                        + "upload; if it does, the .java source itself exceeds codeview.repo-root's "
                        + "configured multipart limits (spring.servlet.multipart.max-request-size)."
        ));
    }
}
