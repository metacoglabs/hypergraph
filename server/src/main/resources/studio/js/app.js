import { api } from './api.js';
import { fill, h, icon, logo, storage, toast } from './dom.js';
import { AdminPage } from './views/admin.js';
import { ConsolePage } from './views/console.js';
import { DashboardPage } from './views/dashboard.js';
import { ExplorePage } from './views/explore.js';
import { loginView } from './views/login.js';
import { SchemaPage } from './views/schema.js';

const PAGES = [
  ['console', 'Console', 'console', ConsolePage],
  ['explore', 'Explore', 'explore', ExplorePage],
  ['schema', 'Schema', 'schema', SchemaPage],
  ['dashboard', 'Dashboard', 'dashboard', DashboardPage],
  ['admin', 'Access', 'admin', AdminPage]
];
const POLL_INTERVAL = 5000;

class Studio {
  constructor(root) {
    this.root = root;
    this.info = null;
    this.session = null;
    this.schema = null;
    this.pages = new Map();
    this.current = null;
    this.poller = null;
    this.applyTheme(storage.get('theme', null));
    window.addEventListener('hstore:unauthenticated', () => this.signedOut());
    window.addEventListener('hstore:offline', () => this.connection?.classList.add('offline'));
    window.addEventListener('hstore:online', () => this.connection?.classList.remove('offline'));
    window.addEventListener('hashchange', () => this.route());
  }

  async start() {
    try {
      this.info = await api.info();
    } catch (error) {
      fill(this.root, h('div', { class: 'login' }, h('div', { class: 'card login-card' }, logo(), h('h1', {}, 'HStore Studio'), h('p', {}, error.message))));
      return;
    }
    if (this.info.session) {
      this.signedIn(this.info.session);
    } else if (!this.info.authenticationRequired) {
      this.signedIn((await api.login()).session);
    } else {
      this.showLogin();
    }
  }

  showLogin() {
    this.stopPolling();
    this.pages.clear();
    this.root.className = '';
    fill(this.root, loginView(session => this.signedIn(session)));
  }

  signedIn(session) {
    this.session = session;
    this.buildShell();
    this.refreshSchema();
    this.route();
    this.startPolling();
  }

  signedOut() {
    if (this.session) {
      this.session = null;
      toast('Your session ended; sign in again.', 'error');
      this.start();
    }
  }

  async logout() {
    await api.logout().catch(() => {});
    this.session = null;
    this.stopPolling();
    this.start();
  }

  buildShell() {
    this.root.className = 'shell';
    this.nav = new Map(PAGES.map(([id, label, iconName]) => [id, h('a', {
      class: 'rail-button', href: `#/${id}`, 'aria-label': label
    }, icon(iconName), h('span', { class: 'tip' }, label))]));
    this.themeButton = h('button', { class: 'rail-button', type: 'button', 'aria-label': 'Toggle theme', onClick: () => this.toggleTheme() },
      icon(this.dark() ? 'sun' : 'moon'), h('span', { class: 'tip' }, 'Toggle theme'));
    this.heading = h('h1', {});
    this.generationLabel = h('strong', {}, this.info.generation);
    this.connection = h('span', { class: 'pill', title: 'Latest durable generation' }, h('span', { class: 'dot' }), 'generation', this.generationLabel);
    this.principal = h('span', { class: 'pill optional' });
    this.branch = h('span', { class: 'pill optional' });
    this.content = h('div', { class: 'page-host' });
    fill(this.root,
      h('nav', { class: 'rail', 'aria-label': 'Sections' },
        logo(),
        [...this.nav.values()],
        h('div', { class: 'rail-spacer' }),
        this.themeButton,
        h('button', { class: 'rail-button', type: 'button', 'aria-label': 'Sign out', onClick: () => this.logout() }, icon('logout'), h('span', { class: 'tip' }, 'Sign out'))),
      h('main', { class: 'main' },
        h('header', { class: 'topbar' },
          this.heading,
          h('span', { class: 'topbar-spacer' }),
          this.branch,
          this.principal,
          this.connection),
        this.content));
    this.renderSession();
  }

  renderSession() {
    const session = this.session;
    fill(this.principal, icon('admin'), h('strong', {}, session.user), `on ${session.tenant}`, h('span', { class: 'badge' }, session.role));
    fill(this.branch, icon('schema'), 'branch', h('strong', {}, session.branch), session.inTransaction ? ' · in transaction' : '');
  }

  page(id) {
    if (!this.pages.has(id)) {
      const [, , , Page] = PAGES.find(([candidate]) => candidate === id);
      const page = new Page(this);
      this.pages.set(id, page);
      this.content.append(page.element);
    }
    return this.pages.get(id);
  }

  route(params = {}) {
    const id = location.hash.replace(/^#\//, '').split('?')[0] || 'console';
    const target = PAGES.some(([candidate]) => candidate === id) ? id : 'console';
    if (this.current && this.current !== target) {
      const previous = this.pages.get(this.current);
      previous.deactivate();
      previous.element.classList.remove('active');
    }
    this.current = target;
    const page = this.page(target);
    page.element.classList.add('active');
    for (const [candidate, link] of this.nav) {
      link.classList.toggle('active', candidate === target);
    }
    fill(this.heading, page.title);
    document.title = `${page.title} · HStore Studio`;
    page.activate(params);
  }

  navigate(id, params = {}) {
    if (location.hash !== `#/${id}`) {
      history.replaceState(null, '', `#/${id}`);
    }
    this.route(params);
  }

  observe(response) {
    if (response.session) {
      this.session = response.session;
      this.renderSession();
    }
    if (response.generation !== undefined) {
      this.generationLabel.textContent = response.generation;
    }
  }

  async refreshSchema() {
    try {
      this.schema = await api.schema();
      return this.schema;
    } catch (error) {
      this.notify(error);
      return null;
    }
  }

  schemaTypes() {
    return this.schema?.types ?? [];
  }

  completions() {
    const entries = [];
    for (const type of this.schemaTypes()) {
      entries.push({ text: type.name, kind: type.kind === 'NODE' ? 'node type' : 'edge type' });
      type.properties.forEach(property => entries.push({ text: property.name, kind: `${type.name} property` }));
      type.roles.forEach(role => entries.push({ text: role, kind: `${type.name} role` }));
    }
    const seen = new Set();
    return entries.filter(entry => !seen.has(entry.text) && seen.add(entry.text));
  }

  startPolling() {
    this.stopPolling();
    this.poller = setInterval(async () => {
      if (document.hidden) {
        return;
      }
      try {
        const info = await api.info();
        this.generationLabel.textContent = info.generation;
      } catch {
      }
    }, POLL_INTERVAL);
  }

  stopPolling() {
    clearInterval(this.poller);
    this.poller = null;
  }

  notify(error) {
    toast(error.code && error.code !== 'INTERNAL' ? `${error.message} [${error.code}]` : error.message, 'error');
  }

  dark() {
    const chosen = document.documentElement.dataset.theme;
    return chosen ? chosen === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
  }

  applyTheme(theme) {
    if (theme) {
      document.documentElement.dataset.theme = theme;
    } else {
      delete document.documentElement.dataset.theme;
    }
    window.dispatchEvent(new CustomEvent('hstore:theme'));
  }

  toggleTheme() {
    const next = this.dark() ? 'light' : 'dark';
    storage.set('theme', next);
    this.applyTheme(next);
    this.themeButton.replaceChildren(icon(this.dark() ? 'sun' : 'moon'), h('span', { class: 'tip' }, 'Toggle theme'));
  }
}

new Studio(document.getElementById('app')).start();
