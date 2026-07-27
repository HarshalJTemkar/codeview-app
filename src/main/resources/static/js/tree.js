(function () {
  const container = document.getElementById('tree-container');
  const panel = document.getElementById('chunk-panel');
  let currentProject = null;

  initProjectPicker(function (project) {
    currentProject = project;
    loadTree(project);
  });

  function loadTree(project) {
    if (!project) {
      container.innerHTML = '<div class="empty-state">No project selected. Upload something first: /ui/upload</div>';
      return;
    }
    fetch('/ui/api/tree?project=' + encodeURIComponent(project))
      .then((res) => res.json())
      .then((nodes) => {
        if (!nodes || !nodes.root) {
          container.innerHTML = '<div class="empty-state">Nothing indexed yet for this project.</div>';
          return;
        }
        container.innerHTML = '';
        const ul = document.createElement('ul');
        ul.appendChild(renderNode('root', nodes));
        container.appendChild(ul);
      })
      .catch((err) => {
        container.innerHTML = '<div class="empty-state">Could not load the tree: ' + err + '</div>';
      });
  }

  function renderNode(nodeId, nodes) {
    const node = nodes[nodeId];
    const li = document.createElement('li');

    const label = document.createElement('span');
    label.className = 'node type-' + node.type;
    label.textContent = node.label;
    li.appendChild(label);

    const hasChunks = node.chunkIds && node.chunkIds.length > 0;
    const hasChildren = node.children && node.children.length > 0;

    if (hasChunks) {
      label.addEventListener('click', () => loadChunk(node.chunkIds[0]));
    }

    if (hasChildren) {
      const childUl = document.createElement('ul');
      childUl.style.display = nodeId === 'root' ? 'block' : 'none';
      node.children.forEach((childId) => childUl.appendChild(renderNode(childId, nodes)));
      li.appendChild(childUl);

      if (!hasChunks) {
        label.addEventListener('click', () => {
          childUl.style.display = childUl.style.display === 'none' ? 'block' : 'none';
        });
      }
    }

    return li;
  }

  function loadChunk(chunkId) {
    fetch('/mcp/code/get_chunk/' + encodeURIComponent(chunkId) + '?project=' + encodeURIComponent(currentProject))
      .then((res) => {
        if (!res.ok) throw new Error('chunk not found');
        return res.json();
      })
      .then((chunk) => {
        panel.style.display = 'block';
        panel.textContent = '// ' + chunk.filePath + '  (' + chunk.astNode + ': ' + chunk.name + ')\n\n' + chunk.text;
      })
      .catch((err) => {
        panel.style.display = 'block';
        panel.textContent = 'Could not load chunk ' + chunkId + ': ' + err;
      });
  }
})();
