(function () {
  const status = document.getElementById('upload-status');

  function showStatus(text, isError) {
    status.style.display = 'block';
    status.style.borderLeft = isError ? '4px solid var(--warning)' : '4px solid var(--primary)';
    status.textContent = text;
  }

  function renderResult(result) {
    const succeeded = result.succeededFiles ? result.succeededFiles.length : 0;
    const failed = result.failedFiles ? result.failedFiles.length : 0;
    let text = 'Indexed into project "' + result.project + '": ' + succeeded + ' file(s), '
        + result.chunksWritten + ' chunk(s) written.';
    if (failed > 0) {
      text += '\n\n' + failed + ' file(s) failed:\n';
      result.failedFiles.forEach((f) => {
        text += '  - ' + f.filePath + ': ' + f.reason + ' (after ' + f.attempts + ' attempt(s))\n';
      });
    }
    text += '\n\nView it: /ui/tree?project=' + encodeURIComponent(result.project)
        + '  or  /ui/flow?project=' + encodeURIComponent(result.project);
    showStatus(text, failed > 0 && succeeded === 0);
  }

  document.getElementById('zip-form').addEventListener('submit', function (e) {
    e.preventDefault();
    const fileInput = document.getElementById('zip-input');
    if (!fileInput.files.length) return;

    const formData = new FormData();
    formData.append('file', fileInput.files[0]);

    showStatus('Uploading and indexing ' + fileInput.files[0].name + '...', false);

    fetch('/mcp/code/upload/zip', { method: 'POST', body: formData })
      .then((res) => {
        if (!res.ok) {
          return res.json()
            .then((body) => { throw new Error((body.error || ('HTTP ' + res.status)) + (body.hint ? '\n' + body.hint : '')); })
            .catch(() => { throw new Error('Upload failed: HTTP ' + res.status); });
        }
        return res.json();
      })
      .then(renderResult)
      .catch((err) => showStatus('Upload failed: ' + err.message, true));
  });

  document.getElementById('folder-form').addEventListener('submit', function (e) {
    e.preventDefault();
    const fileInput = document.getElementById('folder-input');
    if (!fileInput.files.length) return;

    // BUG FIX: this used to append every selected file to the upload
    // regardless of extension, filtering to .java only after the whole
    // request had already been sent — so selecting a project folder that
    // includes build output (target/, .git/, compiled .class/.jar files)
    // could blow past the server's multipart size limit before any
    // filtering happened. Filtering here, before anything is appended,
    // means only source files are ever actually uploaded.
    const formData = new FormData();
    let javaCount = 0;
    let skippedCount = 0;
    for (const file of fileInput.files) {
      if (!file.name.toLowerCase().endsWith('.java')) {
        skippedCount++;
        continue;
      }
      javaCount++;
      // Passing webkitRelativePath as the third arg sets it as the multipart
      // filename, so the server sees the full relative path (e.g.
      // "src/main/java/com/example/Foo.java") rather than just "Foo.java" —
      // that's what lets UploadService reconstruct the folder structure.
      formData.append('files', file, file.webkitRelativePath || file.name);
    }

    if (javaCount === 0) {
      showStatus('No .java files found in the selected folder.', true);
      return;
    }

    showStatus('Uploading ' + javaCount + ' .java file(s) (' + skippedCount
        + ' non-Java file(s) skipped, not uploaded) and indexing...', false);

    fetch('/mcp/code/upload/folder', { method: 'POST', body: formData })
      .then((res) => {
        if (!res.ok) throw new Error('Upload failed: HTTP ' + res.status);
        return res.json();
      })
      .then(renderResult)
      .catch((err) => showStatus('Upload failed: ' + err, true));
  });
})();
