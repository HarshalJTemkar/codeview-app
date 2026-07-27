/**
 * Populates the #project-select dropdown from /ui/api/projects, honors a
 * ?project= URL param as the initial selection, and reloads the page with
 * the new project in the URL whenever the user picks a different one — kept
 * in the URL (not just page state) so a tree/flow view for a specific
 * project is a shareable/bookmarkable link.
 *
 * @param onProjectReady called once with the resolved project name (from the
 *                        URL, or the first available project if none was
 *                        specified) so the calling page can fetch its own data
 */
function initProjectPicker(onProjectReady) {
  const select = document.getElementById('project-select');
  const urlProject = new URLSearchParams(window.location.search).get('project');

  fetch('/ui/api/projects')
    .then((res) => res.json())
    .then((projects) => {
      if (!projects || projects.length === 0) {
        select.innerHTML = '<option value="">(no projects indexed yet)</option>';
        return;
      }

      projects.forEach((name) => {
        const option = document.createElement('option');
        option.value = name;
        option.textContent = name;
        select.appendChild(option);
      });

      const initial = urlProject && projects.includes(urlProject) ? urlProject : projects[0];
      select.value = initial;

      select.addEventListener('change', () => {
        const params = new URLSearchParams(window.location.search);
        params.set('project', select.value);
        window.location.search = params.toString();
      });

      onProjectReady(initial);
    })
    .catch((err) => {
      select.innerHTML = '<option value="">(could not load projects)</option>';
      console.error('Could not load project list:', err);
    });
}
