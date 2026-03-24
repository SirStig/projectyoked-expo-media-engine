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

// Default: dark, regardless of system preference
applyTheme(localStorage.getItem(THEME_KEY) || 'dark');

document.querySelectorAll('.theme-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    applyTheme(html.getAttribute('data-theme') === 'dark' ? 'light' : 'dark');
  });
});

/* ===== SIDEBAR ===== */
const sidebar  = document.getElementById('sidebar');
const overlay  = document.getElementById('sidebarOverlay');
const menuBtn  = document.getElementById('menuBtn');

function openSidebar()  { sidebar?.classList.add('open');    overlay?.classList.add('visible'); }
function closeSidebar() { sidebar?.classList.remove('open'); overlay?.classList.remove('visible'); }

menuBtn?.addEventListener('click', openSidebar);
overlay?.addEventListener('click', closeSidebar);

sidebar?.querySelectorAll('.sidebar-link').forEach(link => {
  link.addEventListener('click', () => { if (window.innerWidth < 900) closeSidebar(); });
});

/* ===== SCROLL SPY ===== */
const spySections  = document.querySelectorAll('[data-section]');
const spyLinks     = document.querySelectorAll('.sidebar-link[href^="#"]');

if (spySections.length && spyLinks.length) {
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

/* ===== COPY BUTTONS ===== */
document.querySelectorAll('pre').forEach(pre => {
  // skip if already wrapped
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
});
