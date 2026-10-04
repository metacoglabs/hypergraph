const integer = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

export function count(value) {
  return integer.format(value ?? 0);
}

export function number(value) {
  return decimal.format(value ?? 0);
}

export function bytes(value) {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let amount = value ?? 0;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) {
    amount /= 1024;
    unit++;
  }
  return `${unit === 0 ? amount : decimal.format(amount)} ${units[unit]}`;
}

export function micros(value) {
  if (value < 1000) {
    return `${integer.format(value)} µs`;
  }
  if (value < 1_000_000) {
    return `${decimal.format(value / 1000)} ms`;
  }
  return `${decimal.format(value / 1_000_000)} s`;
}

export function duration(millis) {
  const seconds = Math.floor(millis / 1000);
  const parts = [[Math.floor(seconds / 86400), 'd'], [Math.floor(seconds / 3600) % 24, 'h'], [Math.floor(seconds / 60) % 60, 'm'], [seconds % 60, 's']];
  const shown = parts.filter(([amount]) => amount > 0).slice(0, 2);
  return shown.length ? shown.map(([amount, unit]) => `${amount}${unit}`).join(' ') : '0s';
}

export function ago(epochMillis) {
  const seconds = Math.round((epochMillis - Date.now()) / 1000);
  const steps = [[60, 'second'], [60, 'minute'], [24, 'hour'], [30, 'day'], [12, 'month'], [Infinity, 'year']];
  let amount = seconds;
  for (const [size, unit] of steps) {
    if (Math.abs(amount) < size) {
      return relative.format(Math.round(amount), unit);
    }
    amount /= size;
  }
  return '';
}

export function timestamp(epochMillis) {
  return new Date(epochMillis).toLocaleString();
}

export function kind(value) {
  return { NODE: 'Node', SET_EDGE: 'Set edge', ORDERED_EDGE: 'Ordered edge' }[value] ?? value;
}

export function plural(amount, word) {
  return `${count(amount)} ${word}${amount === 1 ? '' : 's'}`;
}
