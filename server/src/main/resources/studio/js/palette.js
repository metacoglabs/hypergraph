// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

const HUES = [
  '#6d83f2', '#f2a33a', '#2fbf8f', '#ef5f7f', '#a17cf0', '#2fb3d6',
  '#e676c2', '#8cc34b', '#ff8a4c', '#4fc4b0', '#d0a72a', '#8d9bb5'
];

export function typeColor(typeId) {
  return HUES[(Math.max(1, typeId ?? 1) - 1) % HUES.length];
}

export function alpha(hex, opacity) {
  const value = parseInt(hex.slice(1), 16);
  return `rgba(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255}, ${opacity})`;
}

export function theme() {
  const style = getComputedStyle(document.documentElement);
  const read = name => style.getPropertyValue(name).trim();
  return {
    canvas: read('--canvas'),
    text: read('--text'),
    muted: read('--muted'),
    accent: read('--accent'),
    panel: read('--panel'),
    border: read('--border-strong'),
    font: read('--font')
  };
}
