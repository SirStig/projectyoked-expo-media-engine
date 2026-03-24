/* ===== THEME ===== */
const html = document.documentElement;
const THEME_KEY = 'eme-theme';

function applyTheme(t) {
  html.setAttribute('data-theme', t);
  localStorage.setItem(THEME_KEY, t);
  document.querySelectorAll('.theme-btn').forEach(btn => {
    btn.textContent = t === 'dark' ? '☀ Light' : '◑ Dark';
  });
}

applyTheme(localStorage.getItem(THEME_KEY) || 'dark');

document.querySelectorAll('.theme-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    applyTheme(html.getAttribute('data-theme') === 'dark' ? 'light' : 'dark');
  });
});

/* ===== SIDEBAR ===== */
const sidebar = document.getElementById('sidebar');
const overlay = document.getElementById('sidebarOverlay');
const menuBtn = document.getElementById('menuBtn');

function openSidebar() { sidebar?.classList.add('open'); overlay?.classList.add('visible'); }
function closeSidebar() { sidebar?.classList.remove('open'); overlay?.classList.remove('visible'); }

menuBtn?.addEventListener('click', openSidebar);
overlay?.addEventListener('click', closeSidebar);

sidebar?.querySelectorAll('.sidebar-link').forEach(link => {
  link.addEventListener('click', () => { if (window.innerWidth < 900) closeSidebar(); });
});

/* ===== COPY BUTTONS ===== */
function attachCopyButton(pre) {
  if (pre.parentElement?.classList.contains('code-block')) return;

  const wrap = document.createElement('div');
  wrap.className = 'code-block';
  pre.parentNode.insertBefore(wrap, pre);
  wrap.appendChild(pre);

  const btn = document.createElement('button');
  btn.className = 'copy-btn';
  btn.textContent = 'Copy';
  wrap.appendChild(btn);

  btn.addEventListener('click', () => {
    const text = pre.querySelector('code')?.textContent || pre.textContent;
    navigator.clipboard.writeText(text).then(() => {
      btn.textContent = 'Copied!';
      btn.classList.add('copied');
      setTimeout(() => { btn.textContent = 'Copy'; btn.classList.remove('copied'); }, 2000);
    });
  });
}

document.querySelectorAll('pre').forEach(attachCopyButton);

/* ===== DOC SECTION PANELS (single-section view) ===== */
const DTS_FALLBACK =
  'https://raw.githubusercontent.com/SirStig/projectyoked-expo-media-engine/main/src/index.d.ts';

const panelRoot = document.getElementById('docSectionPanels');
const panelSections = panelRoot
  ? [...panelRoot.querySelectorAll('.doc-section[data-section]')]
  : [];
const panelIds = panelSections.map(s => s.id).filter(Boolean);

function showPanel(id) {
  if (!panelRoot || !panelIds.length) return;
  const resolved = id && panelIds.includes(id) ? id : panelIds[0];
  panelSections.forEach(s => s.classList.toggle('is-active', s.id === resolved));

  document.querySelectorAll('.sidebar-link[href^="#"]').forEach(l => {
    const href = l.getAttribute('href');
    if (href && href.startsWith('#')) {
      l.classList.toggle('active', href === '#' + resolved);
    }
  });

  window.scrollTo({ top: 0, behavior: 'auto' });
  loadTypesIfNeeded(resolved);
}

function loadTypesIfNeeded(activeId) {
  if (activeId !== 'typescript') return;
  const mount = document.querySelector('#typescript .dts-mount');
  if (!mount || mount.dataset.loaded === '1') return;

  const rel = mount.getAttribute('data-dts-asset') || '../assets/index.d.ts';
  const base = document.querySelector('link[rel="canonical"]')?.href || window.location.href;
  const primary = new URL(rel, base).href;

  function fail(msg) {
    mount.innerHTML = '';
    const p = document.createElement('p');
    p.className = 'dts-error';
    p.textContent = msg;
    mount.appendChild(p);
    mount.dataset.loaded = '1';
  }

  function render(text) {
    mount.innerHTML = '';
    const pre = document.createElement('pre');
    const code = document.createElement('code');
    code.textContent = text;
    pre.appendChild(code);
    mount.appendChild(pre);
    attachCopyButton(pre);
    mount.dataset.loaded = '1';
  }

  function loadText(url) {
    return fetch(url, { cache: 'no-cache' }).then(r => {
      if (!r.ok) throw new Error('bad status');
      return r.text();
    });
  }

  loadText(primary)
    .catch(() => loadText(DTS_FALLBACK))
    .then(render)
    .catch(() => fail('Could not load TypeScript definitions.'));
}

if (panelRoot && panelIds.length) {
  const hashId = () => {
    const h = (location.hash || '').slice(1);
    return h && panelIds.includes(h) ? h : panelIds[0];
  };

  showPanel(hashId());
  window.addEventListener('hashchange', () => showPanel(hashId()));

  document.querySelectorAll('.sidebar-link[href^="#"]').forEach(link => {
    link.addEventListener('click', e => {
      const href = link.getAttribute('href');
      if (!href || !href.startsWith('#')) return;
      const hid = href.slice(1);
      if (!panelIds.includes(hid)) return;
      e.preventDefault();
      if (location.hash === href) {
        showPanel(hid);
      } else {
        location.hash = href;
      }
    });
  });
}

/* ===== SCROLL SPY (long single-page mode only) ===== */
const spySections = document.querySelectorAll('[data-section]');
const spyLinks = document.querySelectorAll('.sidebar-link[href^="#"]');

if (!panelRoot && spySections.length && spyLinks.length) {
  const obs = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if (e.isIntersecting) {
        const id = e.target.id;
        spyLinks.forEach(l => l.classList.toggle('active', l.getAttribute('href') === '#' + id));
      }
    });
  }, { rootMargin: '-15% 0px -70% 0px' });
  spySections.forEach(s => obs.observe(s));
}
