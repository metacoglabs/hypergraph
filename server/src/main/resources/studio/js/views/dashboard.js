import { api } from '../api.js';
import { SeriesChart } from '../chart.js';
import { fill, h } from '../dom.js';
import { bytes, count, duration, number, plural, timestamp } from '../format.js';
import { theme } from '../palette.js';

const INTERVAL = 2000;

export class DashboardPage {
  constructor(studio) {
    this.studio = studio;
    this.title = 'Dashboard';
    this.previous = null;
    this.timer = null;
    this.metrics = h('div', { class: 'metrics' });
    this.details = h('div', {});
    const accent = () => theme().accent || '#5b5bd6';
    this.chartSpecs = [
      ['commits', 'Commits / s', () => accent(), value => number(value)],
      ['pagesWritten', 'Pages written / s', () => '#2fbf8f', value => number(value)],
      ['pagesRead', 'Pages read / s', () => '#f2a33a', value => number(value)],
      ['walBytes', 'WAL throughput', () => '#ef5f7f', value => `${bytes(value)}/s`]
    ];
    this.charts = new Map();
    const chartCards = this.chartSpecs.map(([key, label, color, format]) => {
      const host = h('div', { class: 'chart' });
      const current = h('span', { class: 'current' }, '—');
      this.charts.set(key, { host, current, color, format, chart: null });
      return h('div', { class: 'card chart-card' }, h('div', { class: 'card-head' }, h('h3', {}, label), current), host);
    });
    this.denied = h('div', { class: 'card empty' }, 'The dashboard requires the ADMIN role.');
    this.content = h('div', {},
      h('h2', { class: 'section-title' }, 'Engine'),
      this.metrics,
      h('h2', { class: 'section-title' }, 'Throughput'),
      h('div', { class: 'charts' }, chartCards),
      this.details);
    this.element = h('section', { class: 'page' }, h('div', { class: 'page-scroll' }, this.content, this.denied));
    this.denied.style.display = 'none';
  }

  activate() {
    for (const entry of this.charts.values()) {
      entry.chart ??= new SeriesChart(entry.host, { color: entry.color(), format: entry.format });
    }
    this.poll();
    this.timer = setInterval(() => this.poll(), INTERVAL);
  }

  deactivate() {
    clearInterval(this.timer);
    this.timer = null;
  }

  async poll() {
    let stats;
    try {
      stats = await api.stats();
    } catch (error) {
      if (error.status === 403) {
        this.content.style.display = 'none';
        this.denied.style.display = '';
        this.deactivate();
      }
      return;
    }
    if (this.previous) {
      const seconds = Math.max(0.001, (stats.sampledAt - this.previous.sampledAt) / 1000);
      for (const [key, entry] of this.charts) {
        const rate = Math.max(0, (stats.engine[key] - this.previous.engine[key]) / seconds);
        entry.chart.push(rate);
        entry.current.textContent = entry.format(rate);
      }
    }
    this.previous = stats;
    this.studio.observe({ generation: stats.generation });
    this.render(stats);
  }

  render(stats) {
    const engine = stats.engine;
    const metric = (label, value, sub) => h('div', { class: 'card metric' }, h('div', { class: 'label' }, label), h('div', { class: 'value' }, value), sub ? h('div', { class: 'sub' }, sub) : null);
    fill(this.metrics,
      metric('Generation', count(stats.generation), `semantic index at ${count(stats.semanticGeneration)}`),
      metric('Commits', count(engine.commits), `${count(engine.readOnly)} read-only`),
      metric('Conflicts', count(engine.conflicts), `${count(engine.rebases)} rebased`),
      metric('Group commit', number(engine.averageGroupCommit), 'transactions per fsync'),
      metric('Cache hit rate', `${number(engine.cacheHitRate * 100)}%`, `${count(engine.cacheHits)} hits · ${count(engine.cacheMisses)} misses`),
      metric('Data written', bytes(engine.dataBytesWritten), `${count(engine.pagesWritten)} pages`),
      metric('Write-ahead log', bytes(engine.walBytes), `${plural(engine.walSegments, 'segment')} retained`),
      metric('Uptime', duration(stats.sampledAt - stats.startedAt), `since ${timestamp(stats.startedAt)}`));
    const segments = stats.segments;
    const configuration = stats.configuration;
    const recovery = stats.recovery;
    const pair = (label, value) => [h('dt', {}, label), h('dd', {}, value)];
    fill(this.details,
      h('h2', { class: 'section-title' }, 'Storage'),
      h('div', { class: 'split' },
        h('div', { class: 'card' },
          h('div', { class: 'card-head' }, h('h3', {}, 'Data segments'), h('span', { class: 'muted' }, `${count(segments.length)} segments · ${bytes(configuration.segmentBytes)} each`)),
          h('div', { class: 'segments' }, segments.map(segment => {
            const fill = h('span');
            fill.style.height = `${Math.round(Math.min(1, segment.bytes / configuration.segmentBytes) * 100)}%`;
            return h('div', {
              class: `segment ${segment.state}`,
              title: `segment ${segment.id} · ${segment.state.toLowerCase()} · ${bytes(segment.bytes)} · ${count(segment.pages)} nodes, ${bytes(segment.live)} live`
            }, fill);
          })),
          h('div', { class: 'segment-legend' }, ['ACTIVE', 'SEALED', 'COMPACTING', 'RETIRED'].map(state => {
            const dot = h('div', { class: `segment ${state}` }, h('span'));
            dot.style.width = '10px';
            dot.style.height = '10px';
            dot.firstChild.style.height = '100%';
            return h('span', {}, dot, state.toLowerCase());
          }))),
        h('div', { class: 'card' },
          h('div', { class: 'card-head' }, h('h3', {}, 'Configuration & recovery')),
          h('dl', { class: 'kv card-body' },
            pair('Data directory', h('code', {}, stats.directory)),
            pair('Page size', bytes(configuration.pageSize)),
            pair('Durability', configuration.durability),
            pair('WAL mode', configuration.walMode),
            pair('Node cache', bytes(configuration.cacheBytes)),
            pair('History limit', `${count(configuration.historyLimit)} generations`),
            pair('Checkpoint after', bytes(configuration.checkpointWalBytes)),
            pair('Change feed', bytes(engine.feedBytes)),
            pair('Studio sessions', count(stats.studioSessions)),
            pair('Replayed at start', `${count(recovery.replayedCommits)} commits`),
            pair('Discarded at start', `${count(recovery.discardedTransactions)} transactions · ${count(recovery.discardedCommits)} commits`)))));
  }
}
