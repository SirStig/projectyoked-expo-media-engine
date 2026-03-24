(function () {
  var body = document.getElementById('changelog-body');
  var status = document.getElementById('changelog-status');
  if (!body || !status) return;

  var path = 'changelog.md';
  fetch(path, { cache: 'no-cache' })
    .then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.text();
    })
    .then(function (text) {
      if (typeof marked !== 'undefined' && marked.parse) {
        body.innerHTML = marked.parse(text);
      } else {
        throw new Error('marked not loaded');
      }
      status.textContent = 'Source: repository CHANGELOG.md (included with this site deploy).';
    })
    .catch(function () {
      status.innerHTML =
        'Changelog file could not be loaded. If you are running the site locally, copy the repo root <code>CHANGELOG.md</code> to <code>docs/changelog.md</code>. ' +
        'You can also view the file on ' +
        '<a href="https://github.com/SirStig/projectyoked-expo-media-engine/blob/main/CHANGELOG.md">GitHub</a>.';
      status.classList.add('banner');
    });
})();
