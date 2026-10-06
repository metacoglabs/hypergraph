// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import { api, hql, identifier } from '../api.js';
import { button, clear, confirmDialog, fill, h, toast } from '../dom.js';
import { count } from '../format.js';

const ROLES = ['READER', 'WRITER', 'ADMIN'];

export class AdminPage {
  constructor(studio) {
    this.studio = studio;
    this.title = 'Access';
    this.tables = h('div', {});
    this.forms = h('div', { class: 'form' });
    this.element = h('section', { class: 'page' }, h('div', { class: 'page-scroll' }, h('div', { class: 'admin-grid' }, this.tables, this.forms)));
  }

  async activate() {
    if (this.studio.session?.role !== 'ADMIN') {
      fill(this.tables, h('div', { class: 'card empty' }, 'Managing tenants and users requires the ADMIN role.'));
      clear(this.forms);
      return;
    }
    await this.load();
  }

  deactivate() {}

  async execute(script) {
    const response = await api.query(script);
    this.studio.observe(response);
    const failure = response.results.find(result => result.error);
    if (failure) {
      throw new Error(failure.error.message);
    }
    return response.results;
  }

  async load() {
    try {
      const [tenants, users] = await this.execute('SHOW TENANTS; SHOW USERS;');
      this.render(tenants, users);
    } catch (error) {
      this.studio.notify(error);
    }
  }

  render(tenants, users) {
    const table = (result, actions) => h('div', { class: 'table-wrap' }, h('table', { class: 'grid' },
      h('thead', {}, h('tr', {}, result.columns.map(column => h('th', {}, column)), actions ? h('th', {}) : null)),
      h('tbody', {}, result.rows.map(row => h('tr', {},
        row.map(value => h('td', { class: typeof value === 'number' ? 'number' : '' }, value === null ? h('span', { class: 'null' }, '—') : typeof value === 'number' ? count(value) : String(value))),
        actions ? h('td', {}, actions(row)) : null)))));
    fill(this.tables,
      h('h2', { class: 'section-title' }, 'Tenants'),
      h('div', { class: 'card' }, table(tenants)),
      h('h2', { class: 'section-title' }, 'Users'),
      h('div', { class: 'card' }, users.rows.length ? table(users, row => button('Drop', {
        variant: 'small danger', onClick: () => this.dropUser(row[0])
      })) : h('div', { class: 'empty' }, 'No users exist; the server accepts unauthenticated connections until one is created.')));
    const tenantNames = tenants.rows.map(row => row[1]);
    fill(this.forms, this.userForm(tenantNames), this.tenantForm());
  }

  userForm(tenants) {
    const name = h('input', { class: 'input', required: true, autocomplete: 'off' });
    const password = h('input', { class: 'input', type: 'password', required: true, autocomplete: 'new-password' });
    const tenant = h('select', { class: 'select' }, tenants.map(value => h('option', { value }, value)));
    const role = h('select', { class: 'select' }, ROLES.map(value => h('option', { value, selected: value === 'WRITER' }, value.toLowerCase())));
    const error = h('div', { class: 'form-error' });
    const submit = async event => {
      event.preventDefault();
      error.textContent = '';
      try {
        await this.execute(`CREATE USER ${hql(name.value)} PASSWORD ${hql(password.value)} TENANT ${identifier(tenant.value)} ROLE ${role.value};`);
        toast(`Created user ${name.value}`);
        await this.load();
      } catch (failure) {
        error.textContent = failure.message;
      }
    };
    return h('form', { class: 'card', onSubmit: submit },
      h('div', { class: 'card-head' }, h('h3', {}, 'Create user')),
      h('div', { class: 'card-body form' },
        h('div', { class: 'field' }, h('label', {}, 'Name'), name),
        h('div', { class: 'field' }, h('label', {}, 'Password'), password),
        h('div', { class: 'form-row' }, h('div', { class: 'field' }, h('label', {}, 'Tenant'), tenant), h('div', { class: 'field' }, h('label', {}, 'Role'), role)),
        error,
        h('button', { class: 'button primary', type: 'submit' }, 'Create user')));
  }

  tenantForm() {
    const name = h('input', { class: 'input', required: true, pattern: '[A-Za-z_][A-Za-z0-9_]*', autocomplete: 'off' });
    const atoms = h('input', { class: 'input', type: 'number', min: 1, placeholder: 'unlimited' });
    const edges = h('input', { class: 'input', type: 'number', min: 1, placeholder: 'unlimited' });
    const error = h('div', { class: 'form-error' });
    const submit = async event => {
      event.preventDefault();
      error.textContent = '';
      const quota = [['atoms', atoms.value], ['edges', edges.value]].filter(([, value]) => value).map(([key, value]) => `${key} ${Number(value)}`);
      try {
        await this.execute(`CREATE TENANT ${name.value}${quota.length ? ` QUOTA (${quota.join(', ')})` : ''};`);
        toast(`Created tenant ${name.value}`);
        await this.load();
      } catch (failure) {
        error.textContent = failure.message;
      }
    };
    return h('form', { class: 'card', onSubmit: submit },
      h('div', { class: 'card-head' }, h('h3', {}, 'Create tenant')),
      h('div', { class: 'card-body form' },
        h('div', { class: 'field' }, h('label', {}, 'Name'), name),
        h('div', { class: 'form-row' }, h('div', { class: 'field' }, h('label', {}, 'Max atoms'), atoms), h('div', { class: 'field' }, h('label', {}, 'Max edges'), edges)),
        error,
        h('button', { class: 'button primary', type: 'submit' }, 'Create tenant')));
  }

  async dropUser(name) {
    const confirmed = await confirmDialog({ title: `Drop user ${name}?`, message: 'The user can no longer sign in. Data they wrote is kept.', confirm: 'Drop user', danger: true });
    if (!confirmed) {
      return;
    }
    try {
      await this.execute(`DROP USER ${hql(name)};`);
      toast(`Dropped user ${name}`);
      await this.load();
    } catch (error) {
      this.studio.notify(error);
    }
  }
}
