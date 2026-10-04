import { api } from '../api.js';
import { h, logo } from '../dom.js';

export function loginView(onSignedIn) {
  const user = h('input', { class: 'input', autocomplete: 'username', required: true, autofocus: true });
  const password = h('input', { class: 'input', type: 'password', autocomplete: 'current-password', required: true });
  const error = h('div', { class: 'form-error', role: 'alert' });
  const submit = h('button', { class: 'button primary', type: 'submit' }, 'Sign in');
  const form = h('form', { class: 'card login-card', onSubmit: async event => {
    event.preventDefault();
    submit.disabled = true;
    error.textContent = '';
    try {
      const response = await api.login(user.value, password.value);
      onSignedIn(response.session);
    } catch (failure) {
      error.textContent = failure.status === 401 ? 'The user name or password is incorrect.' : failure.message;
      password.select();
    } finally {
      submit.disabled = false;
    }
  } },
  logo(),
  h('h1', {}, 'HStore Studio'),
  h('p', {}, 'Sign in with a database user.'),
  h('div', { class: 'form' },
    h('div', { class: 'field' }, h('label', {}, 'User'), user),
    h('div', { class: 'field' }, h('label', {}, 'Password'), password),
    error,
    submit));
  setTimeout(() => user.focus());
  return h('div', { class: 'login' }, form);
}
