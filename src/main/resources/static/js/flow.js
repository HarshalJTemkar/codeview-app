(function () {
  const container = document.getElementById('flow-graph');
  const emptyState = document.getElementById('flow-empty');

  fetch('/ui/api/flow')
    .then((res) => res.json())
    .then((graph) => {
      if (!graph.nodes || graph.nodes.length === 0) {
        container.style.display = 'none';
        emptyState.style.display = 'block';
        return;
      }

      const nodes = new vis.DataSet(
        graph.nodes.map((n) => ({
          id: n.id,
          label: n.label,
          shape: n.type === 'ClassOrInterfaceDeclaration' ? 'box' : 'ellipse',
          color: {
            background: n.type === 'ClassOrInterfaceDeclaration' ? '#0070d1' : '#181818',
            border: '#0064b7',
          },
          font: { color: '#ffffff' },
          title: n.filePath,
        }))
      );

      const edges = new vis.DataSet(
        graph.edges.map((e) => ({ from: e.from, to: e.to, arrows: 'to', color: '#6b6b6b' }))
      );

      new vis.Network(container, { nodes, edges }, {
        layout: { improvedLayout: true },
        physics: { stabilization: true, barnesHut: { gravitationalConstant: -4000 } },
        interaction: { hover: true },
      });
    })
    .catch((err) => {
      container.style.display = 'none';
      emptyState.style.display = 'block';
      emptyState.textContent = 'Could not load the flow graph: ' + err;
    });
})();
