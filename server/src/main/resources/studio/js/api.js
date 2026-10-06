// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

export class ApiError extends Error {
  constructor(status, code, message, retryable = false) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = retryable;
  }
}

async function call(method, path, body) {
  let response;
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: method === 'POST' ? { 'Content-Type': 'application/json', 'X-HStore-Studio': '1' } : {},
      body: body === undefined ? undefined : JSON.stringify(body)
    });
  } catch (failure) {
    window.dispatchEvent(new CustomEvent('hstore:offline'));
    throw new ApiError(0, 'UNREACHABLE', 'the server is unreachable');
  }
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = payload.error ?? {};
    if (response.status === 401 && path !== '/api/login') {
      window.dispatchEvent(new CustomEvent('hstore:unauthenticated'));
    }
    throw new ApiError(response.status, error.code ?? `HTTP_${response.status}`, error.message ?? response.statusText, error.retryable);
  }
  window.dispatchEvent(new CustomEvent('hstore:online'));
  return payload;
}

const query = parameters => {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(parameters)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(name, Array.isArray(value) ? value.join(',') : String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
};

export const api = {
  info: () => call('GET', '/api/info'),
  login: (user, password) => call('POST', '/api/login', user ? { user, password } : {}),
  logout: () => call('POST', '/api/logout', {}),
  query: script => call('POST', '/api/query', { script }),
  graph: parameters => call('GET', `/api/graph${query(parameters)}`),
  schema: () => call('GET', '/api/schema'),
  history: () => call('GET', '/api/history'),
  stats: () => call('GET', '/api/stats')
};

export function hql(text) {
  return `'${String(text).replaceAll("'", "''")}'`;
}

export function identifier(text) {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(text) ? text : hql(text);
}
