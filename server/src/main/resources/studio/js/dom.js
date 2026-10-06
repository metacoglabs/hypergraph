// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

const SVG = 'http://www.w3.org/2000/svg';

const ICONS = {
  console: 'M4 17l6-5-6-5M12 19h8',
  explore: 'M12 4.5l6.5 11.2H5.5zM12 4.5a2 2 0 1 0 0 .01M5.5 15.7a2 2 0 1 0 0 .01M18.5 15.7a2 2 0 1 0 0 .01M12 12.3v.01',
  schema: 'M12 3l9 4.5-9 4.5-9-4.5zM3 12l9 4.5 9-4.5M3 16.5L12 21l9-4.5',
  dashboard: 'M3 12h4l3-8 4 16 3-8h4',
  admin: 'M16 19v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 17.5V19M10 10.5a3 3 0 1 0 0-6 3 3 0 0 0 0 6M20 19v-1.5a3.5 3.5 0 0 0-2.5-3.35M15.5 4.6a3 3 0 0 1 0 5.8',
  sun: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z',
  logout: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l-5-5 5-5M5 12h11',
  play: 'M7 4.5v15l12-7.5z',
  history: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3.5 2',
  book: 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5zM4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5',
  trash: 'M4 7h16M10 11v6M14 11v6M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-12M9 7V4h6v3',
  close: 'M6 6l12 12M18 6L6 18',
  refresh: 'M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4',
  fit: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  plus: 'M12 5v14M5 12h14',
  minus: 'M5 12h14',
  image: 'M4 5h16v14H4zM4 16l5-5 4 4 2-2 5 5M15.5 9.5v.01',
  copy: 'M9 9h11v11H9zM5 15H4V4h11v1',
  pin: 'M12 17v5M9 3h6l-1 6 3 3v2H7v-2l3-3z',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14M20 20l-4-4',
  table: 'M3 5h18v14H3zM3 10h18M3 15h18M9 5v14',
  braces: 'M8 4H7a2 2 0 0 0-2 2v4l-2 2 2 2v4a2 2 0 0 0 2 2h1M16 4h1a2 2 0 0 1 2 2v4l2 2-2 2v4a2 2 0 0 1-2 2h-1',
  graph: 'M6 6.5a2.5 2.5 0 1 0 0-.01M18 6.5a2.5 2.5 0 1 0 0-.01M12 18.5a2.5 2.5 0 1 0 0-.01M8.2 7.6l2.6 8.6M15.8 7.6l-2.6 8.6M8.5 6h7',
  plan: 'M4 6h10M4 12h16M4 18h7M18 4v4M15 6h6',
  chevron: 'M6 9l6 6 6-6',
  expand: 'M12 5v14M5 12h14M12 2v.01',
  eyeOff: 'M3 3l18 18M10.6 10.6a2 2 0 0 0 2.8 2.8M9.9 5.1A9.8 9.8 0 0 1 12 5c5 0 9 5 9 7a10.5 10.5 0 0 1-2.4 3.2M6.6 6.6C4.4 8 3 10.4 3 12c0 2 4 7 9 7a9.6 9.6 0 0 0 5.4-1.6',
  collapse: 'M18 15l-6-6-6 6',
  reheat: 'M12 3c1.5 3 5 5 5 9a5 5 0 0 1-10 0c0-2 1-3 2-4 0 2 1 3 2 3 0-3-1-5 1-8z',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18M12 7v5l3 2',
  live: 'M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2M8.5 15.5a5 5 0 0 1 0-7M15.5 8.5a5 5 0 0 1 0 7M5.6 18.4a9 9 0 0 1 0-12.8M18.4 5.6a9 9 0 0 1 0 12.8'
};

export function h(tag, attributes = {}, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes ?? {})) {
    if (value === undefined || value === null || value === false) {
      continue;
    }
    if (name === 'class') {
      element.className = value;
    } else if (name === 'dataset') {
      Object.assign(element.dataset, value);
    } else if (name.startsWith('on') && typeof value === 'function') {
      element.addEventListener(name.slice(2).toLowerCase(), value);
    } else if (name in element && typeof value !== 'string') {
      element[name] = value;
    } else {
      element.setAttribute(name, value === true ? '' : value);
    }
  }
  append(element, children);
  return element;
}

function append(element, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) {
      continue;
    }
    element.append(child instanceof Node ? child : String(child));
  }
}

export function icon(name) {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', 'icon-svg');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(SVG, 'path');
  path.setAttribute('d', ICONS[name] ?? '');
  svg.append(path);
  return svg;
}

export function logo() {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 32 32');
  svg.setAttribute('class', 'rail-logo');
  svg.setAttribute('aria-label', 'HStore');
  const shapes = [
    ['rect', { width: 32, height: 32, rx: 8, fill: 'var(--accent)' }],
    ['path', { d: 'M9.5 21.5 16 9l6.5 12.5z', fill: '#fff', 'fill-opacity': 0.22, stroke: '#fff', 'stroke-opacity': 0.55, 'stroke-width': 1.2, 'stroke-linejoin': 'round' }],
    ['circle', { cx: 16, cy: 9, r: 3, fill: '#fff' }],
    ['circle', { cx: 9.5, cy: 21.5, r: 3, fill: '#fff' }],
    ['circle', { cx: 22.5, cy: 21.5, r: 3, fill: '#fff' }],
    ['circle', { cx: 16, cy: 17.3, r: 1.6, fill: '#fff' }]
  ];
  for (const [tag, attributes] of shapes) {
    const shape = document.createElementNS(SVG, tag);
    for (const [name, value] of Object.entries(attributes)) {
      shape.setAttribute(name, value);
    }
    svg.append(shape);
  }
  return svg;
}

export function button(label, { icon: iconName, variant = '', title, onClick, kbd, disabled } = {}) {
  const classes = ['button', ...variant.split(' ').filter(Boolean)];
  if (!label) {
    classes.push('icon');
  }
  return h('button', { class: classes.join(' '), type: 'button', title: title ?? label, 'aria-label': title ?? label, onClick, disabled },
    iconName ? icon(iconName) : null, label || null, kbd ? h('kbd', {}, kbd) : null);
}

export function clear(element) {
  element.replaceChildren();
  return element;
}

export function fill(element, ...children) {
  element.replaceChildren();
  append(element, children);
  return element;
}

const toastHost = () => document.querySelector('.toasts') ?? document.body.appendChild(h('div', { class: 'toasts', role: 'status' }));

export function toast(message, kind = 'info') {
  const element = h('div', { class: `toast ${kind}` }, message);
  toastHost().append(element);
  setTimeout(() => element.remove(), kind === 'error' ? 6500 : 3500);
}

export function confirmDialog({ title, message, confirm = 'Confirm', danger = false }) {
  return new Promise(resolve => {
    const close = result => {
      backdrop.remove();
      document.removeEventListener('keydown', onKey);
      resolve(result);
    };
    const onKey = event => {
      if (event.key === 'Escape') {
        close(false);
      }
    };
    const confirmButton = button(confirm, { variant: danger ? 'primary danger' : 'primary', onClick: () => close(true) });
    const backdrop = h('div', { class: 'modal-backdrop', onClick: event => event.target === backdrop && close(false) },
      h('div', { class: 'modal card', role: 'dialog', 'aria-modal': 'true' },
        h('h2', {}, title),
        h('p', {}, message),
        h('div', { class: 'modal-actions' }, button('Cancel', { onClick: () => close(false) }), confirmButton)));
    document.body.append(backdrop);
    document.addEventListener('keydown', onKey);
    confirmButton.focus();
  });
}

export function popover(anchor, content) {
  document.querySelectorAll('.popover').forEach(existing => existing.remove());
  const box = anchor.getBoundingClientRect();
  const element = h('div', { class: 'popover' }, content);
  document.body.append(element);
  const width = element.offsetWidth;
  element.style.left = `${Math.max(8, Math.min(box.left, window.innerWidth - width - 8))}px`;
  element.style.top = `${box.bottom + 6}px`;
  const dismiss = event => {
    if (!element.contains(event.target) && !anchor.contains(event.target)) {
      close();
    }
  };
  const onKey = event => event.key === 'Escape' && close();
  const close = () => {
    element.remove();
    document.removeEventListener('pointerdown', dismiss, true);
    document.removeEventListener('keydown', onKey);
  };
  setTimeout(() => {
    document.addEventListener('pointerdown', dismiss, true);
    document.addEventListener('keydown', onKey);
  });
  return close;
}

export const storage = {
  get(key, fallback) {
    try {
      const value = localStorage.getItem(`hstore.studio.${key}`);
      return value === null ? fallback : JSON.parse(value);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(`hstore.studio.${key}`, JSON.stringify(value));
    } catch {
    }
  }
};
