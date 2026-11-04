/* Front-end for the stock trading simulator.
 *
 * OOP mirrors the server: ApiClient talks to the API, MarketTable renders the
 * quotes, TradePanel places orders, PortfolioPanel values holdings,
 * PerformanceChart draws equity over time, and App wires them together.
 */

const money = (n) =>
  (n < 0 ? '-$' : '$') +
  Math.abs(Number(n) || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const signedMoney = (n) => {
  const v = Number(n) || 0;
  return (v >= 0 ? '+' : '-') + money(Math.abs(v));
};

const pct = (n) => `${n >= 0 ? '+' : ''}${Number(n).toFixed(2)}%`;

const cls = (n) => (n > 0 ? 'up' : n < 0 ? 'down' : 'flat');

const volume = (n) =>
  n >= 1e9 ? `${(n / 1e9).toFixed(2)}B` : n >= 1e6 ? `${(n / 1e6).toFixed(2)}M` : `${(n / 1e3).toFixed(1)}K`;

const esc = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

class ApiClient {
  async get(path) {
    const res = await fetch(path);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  }

  async post(path, body) {
    const res = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  }
}

/** Renders the live quote table with sparklines and quick trade buttons. */
class MarketTable {
  constructor(tbody, onSelectSymbol) {
    this.tbody = tbody;
    this.onSelectSymbol = onSelectSymbol;
    this.stocks = [];
    this.prevPrices = new Map();
  }

  update(stocks) {
    const flash = new Map();
    for (const s of stocks) {
      const prev = this.prevPrices.get(s.symbol);
      if (prev !== undefined && prev !== s.price) flash.set(s.symbol, s.price > prev ? 'up' : 'down');
      this.prevPrices.set(s.symbol, s.price);
    }
    this.stocks = stocks;
    this.render(flash);
  }

  sparkline(values) {
    if (!values || values.length < 2) return '';
    const w = 92;
    const h = 24;
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = max - min || 1;
    const pts = values
      .map((v, i) => {
        const x = (i / (values.length - 1)) * w;
        const y = h - ((v - min) / span) * (h - 4) - 2;
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
    const rising = values[values.length - 1] >= values[0];
    const color = rising ? '#35d07f' : '#ff5f6d';
    return `<svg class="spark" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><polyline points="${pts}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linejoin="round"/></svg>`;
  }

  render(flash = new Map()) {
    if (this.stocks.length === 0) {
      this.tbody.innerHTML = '<tr><td colspan="8" class="empty">No market data</td></tr>';
      return;
    }
    this.tbody.innerHTML = this.stocks
      .map((s) => {
        const flashCls = flash.get(s.symbol);
        const priceCls = flashCls ? (flashCls === 'up' ? 'up' : 'down') : '';
        const rangeWidth = Math.max(
          0,
          Math.min(100, ((s.price - s.dayLow) / Math.max(0.01, s.dayHigh - s.dayLow)) * 100)
        );
        return `
        <tr data-symbol="${esc(s.symbol)}">
          <td class="sym">${esc(s.symbol)}</td>
          <td class="name">${esc(s.name)}</td>
          <td class="num ${priceCls}">${money(s.price)}</td>
          <td class="num ${cls(s.changePercent)}">${pct(s.changePercent)}<br><span class="muted">${signedMoney(s.change)}</span></td>
          <td class="num"><span class="muted">${money(s.dayLow)} – ${money(s.dayHigh)}</span>
            <span class="range" style="display:block;height:3px;background:#232d40;border-radius:2px;margin-top:3px">
              <span style="display:block;height:3px;width:${rangeWidth}%;background:${cls(s.changePercent) === 'down' ? '#ff5f6d' : '#35d07f'};border-radius:2px"></span>
            </span>
          </td>
          <td>${this.sparkline(s.history)}</td>
          <td class="num muted">${volume(s.volume)}</td>
          <td class="num">
            <button class="btn mini buy-btn" data-action="buy" data-symbol="${esc(s.symbol)}">Buy</button>
            <button class="btn mini sell-btn" data-action="sell" data-symbol="${esc(s.symbol)}">Sell</button>
          </td>
        </tr>`;
      })
      .join('');
  }
}

/** The order ticket: symbol, side, quantity, live quote and submission. */
class TradePanel {
  constructor(api, els, { onQuoteChange, onExecuted }) {
    this.api = api;
    this.els = els;
    this.onQuoteChange = onQuoteChange;
    this.onExecuted = onExecuted;
    this.side = 'BUY';
    this.stocks = [];
    this.holdings = [];
    this.cash = 0;
    this.bind();
  }

  bind() {
    this.els.form.addEventListener('submit', (e) => {
      e.preventDefault();
      this.submit();
    });
    this.els.sideToggle.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-side]');
      if (btn) this.setSide(btn.dataset.side);
    });
    this.els.symbol.addEventListener('change', () => this.refreshQuote());
    this.els.qty.addEventListener('input', () => this.refreshQuote());
  }

  setStocks(stocks) {
    const current = this.els.symbol.value;
    this.stocks = stocks;
    this.els.symbol.innerHTML = stocks
      .map((s) => `<option value="${esc(s.symbol)}">${esc(s.symbol)} — ${esc(s.name)}</option>`)
      .join('');
    if (stocks.some((s) => s.symbol === current)) this.els.symbol.value = current;
    this.refreshQuote();
  }

  setAccount({ cash, positions }) {
    this.cash = cash;
    this.holdings = positions || [];
    this.refreshQuote();
  }

  setSide(side) {
    this.side = side;
    for (const btn of this.els.sideToggle.querySelectorAll('.side-btn')) {
      btn.classList.toggle('active', btn.dataset.side === side);
    }
    this.els.submit.textContent = side === 'BUY' ? 'Place buy order' : 'Place sell order';
    this.els.quoteLabel.textContent = side === 'BUY' ? 'Estimated cost' : 'Estimated proceeds';
    this.refreshQuote();
  }

  setSymbol(symbol) {
    if ([...this.els.symbol.options].some((o) => o.value === symbol)) {
      this.els.symbol.value = symbol;
      this.refreshQuote();
    }
  }

  ownedQuantity(symbol) {
    return this.holdings.find((h) => h.symbol === symbol)?.quantity ?? 0;
  }

  refreshQuote() {
    const symbol = this.els.symbol.value;
    const stock = this.stocks.find((s) => s.symbol === symbol);
    const qty = Math.max(0, Math.floor(Number(this.els.qty.value) || 0));
    const price = stock?.price ?? 0;
    const amount = price * qty;
    const owned = this.ownedQuantity(symbol);

    this.els.quotePrice.textContent = stock ? money(price) : '—';
    this.els.quoteTotal.textContent = stock ? money(amount) : '—';
    this.els.quotePosition.textContent = stock
      ? this.side === 'BUY'
        ? `${owned + qty} shares (cash left ${money(this.cash - amount)})`
        : `${Math.max(0, owned - qty)} shares`
      : '—';
    this.onQuoteChange?.({ symbol, side: this.side, qty, price, amount });
  }

  async submit() {
    const symbol = this.els.symbol.value;
    const quantity = Math.floor(Number(this.els.qty.value) || 0);
    this.els.error.hidden = true;
    try {
      const result = await this.api.post('/api/trade', {
        symbol,
        quantity,
        side: this.side,
        userId: window.app.userId,
      });
      this.els.error.hidden = true;
      this.onExecuted?.(result);
    } catch (err) {
      this.els.error.textContent = err.message;
      this.els.error.hidden = false;
    }
  }
}

/** Summary stats + holdings table. */
class PortfolioPanel {
  constructor(els) {
    this.els = els;
  }

  render(portfolio) {
    const p = portfolio;
    this.els.total.textContent = money(p.totalValue);
    this.els.cash.textContent = money(p.cash);
    this.els.positions.textContent = money(p.positionsValue);
    this.els.pnl.textContent = `${signedMoney(p.totalPnl)} (${pct(p.totalPnlPercent)})`;
    this.els.pnl.className = `stat-value ${cls(p.totalPnl)}`;
    this.els.open.textContent = String(p.positions.length);

    this.els.holdings.innerHTML = p.positions.length
      ? p.positions
          .map(
            (h) => `
        <tr data-symbol="${esc(h.symbol)}">
          <td class="sym">${esc(h.symbol)}</td>
          <td class="num">${h.quantity}</td>
          <td class="num">${money(h.avgCost)}</td>
          <td class="num">${money(h.marketValue)}</td>
          <td class="num ${cls(h.unrealizedPnl)}">${signedMoney(h.unrealizedPnl)}<br><span class="muted">${pct(h.unrealizedPnlPercent)}</span></td>
        </tr>`
          )
          .join('')
      : '<tr><td colspan="5" class="empty">No positions yet — buy some stock!</td></tr>';
  }
}

/** Transaction history table. */
class ActivityTable {
  constructor(tbody) {
    this.tbody = tbody;
  }

  render(transactions) {
    this.tbody.innerHTML = transactions.length
      ? transactions
          .map(
            (t) => `
        <tr>
          <td class="muted">${esc((t.createdAt || '').replace('T', ' ').slice(0, 19))}</td>
          <td><span class="badge ${t.side.toLowerCase()}">${t.side}</span></td>
          <td class="sym">${esc(t.symbol)}</td>
          <td class="num">${t.quantity}</td>
          <td class="num">${money(t.price)}</td>
          <td class="num">${money(t.total)}</td>
          <td class="num ${t.realizedPnl === null ? 'flat' : cls(t.realizedPnl)}">${t.realizedPnl === null ? '—' : signedMoney(t.realizedPnl)}</td>
        </tr>`
          )
          .join('')
      : '<tr><td colspan="7" class="empty">No trades yet</td></tr>';
  }
}

/** Canvas line chart of portfolio value and cash over time. */
class PerformanceChart {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.data = [];
    window.addEventListener('resize', () => this.draw());
  }

  setData(history) {
    this.data = history || [];
    this.draw();
  }

  draw() {
    const canvas = this.canvas;
    const dpr = window.devicePixelRatio || 1;
    const width = canvas.clientWidth || 600;
    const height = 220;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    const ctx = this.ctx;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);

    const pad = { l: 62, r: 12, t: 14, b: 24 };
    const plotW = width - pad.l - pad.r;
    const plotH = height - pad.t - pad.b;

    const data = this.data;
    if (data.length < 2) {
      ctx.fillStyle = '#8a97ad';
      ctx.font = '12px system-ui';
      ctx.textAlign = 'center';
      ctx.fillText('Collecting performance samples…', width / 2, height / 2);
      return;
    }

    const values = data.flatMap((d) => [d.value, d.cash]);
    let min = Math.min(...values);
    let max = Math.max(...values);
    const spread = max - min || Math.max(1, max * 0.01);
    min -= spread * 0.12;
    max += spread * 0.12;

    const x = (i) => pad.l + (i / (data.length - 1)) * plotW;
    const y = (v) => pad.t + plotH - ((v - min) / (max - min)) * plotH;

    // grid + y labels
    ctx.strokeStyle = 'rgba(35,45,64,0.9)';
    ctx.fillStyle = '#8a97ad';
    ctx.font = '11px system-ui';
    ctx.textAlign = 'right';
    ctx.lineWidth = 1;
    const lines = 4;
    for (let i = 0; i <= lines; i++) {
      const val = min + ((max - min) * i) / lines;
      const yy = y(val);
      ctx.beginPath();
      ctx.moveTo(pad.l, yy);
      ctx.lineTo(width - pad.r, yy);
      ctx.stroke();
      ctx.fillText(money(val), pad.l - 8, yy + 4);
    }

    // x labels (first / middle / last)
    ctx.textAlign = 'center';
    const labelAt = (i) => {
      const t = data[i].t || '';
      return t.slice(11, 16) || t.slice(0, 10);
    };
    ctx.fillText(labelAt(0), x(0), height - 6);
    ctx.fillText(labelAt(Math.floor((data.length - 1) / 2)), x(Math.floor((data.length - 1) / 2)), height - 6);
    ctx.fillText(labelAt(data.length - 1), x(data.length - 1), height - 6);

    // cash line (dashed green)
    ctx.setLineDash([5, 4]);
    ctx.strokeStyle = 'rgba(53,208,127,0.75)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    data.forEach((d, i) => (i === 0 ? ctx.moveTo(x(i), y(d.cash)) : ctx.lineTo(x(i), y(d.cash))));
    ctx.stroke();
    ctx.setLineDash([]);

    // value area + line
    const grad = ctx.createLinearGradient(0, pad.t, 0, height - pad.b);
    grad.addColorStop(0, 'rgba(79,140,255,0.35)');
    grad.addColorStop(1, 'rgba(79,140,255,0.02)');
    ctx.beginPath();
    data.forEach((d, i) => (i === 0 ? ctx.moveTo(x(i), y(d.value)) : ctx.lineTo(x(i), y(d.value))));
    ctx.lineTo(x(data.length - 1), height - pad.b);
    ctx.lineTo(x(0), height - pad.b);
    ctx.closePath();
    ctx.fillStyle = grad;
    ctx.fill();

    ctx.beginPath();
    data.forEach((d, i) => (i === 0 ? ctx.moveTo(x(i), y(d.value)) : ctx.lineTo(x(i), y(d.value))));
    ctx.strokeStyle = '#4f8cff';
    ctx.lineWidth = 2;
    ctx.stroke();

    // last point marker
    const last = data[data.length - 1];
    ctx.beginPath();
    ctx.arc(x(data.length - 1), y(last.value), 3.5, 0, Math.PI * 2);
    ctx.fillStyle = '#4f8cff';
    ctx.fill();
  }
}

/** Top-level controller: polling, state and user switching. */
class App {
  constructor() {
    this.api = new ApiClient();
    this.userId = null;
    this.stocks = [];

    this.marketTable = new MarketTable(document.getElementById('market-body'));
    this.portfolioPanel = new PortfolioPanel({
      total: document.getElementById('stat-total'),
      cash: document.getElementById('stat-cash'),
      positions: document.getElementById('stat-positions'),
      pnl: document.getElementById('stat-pnl'),
      open: document.getElementById('stat-open'),
      holdings: document.getElementById('holdings-body'),
    });
    this.activity = new ActivityTable(document.getElementById('txn-body'));
    this.chart = new PerformanceChart(document.getElementById('perf-chart'));
    this.tradePanel = new TradePanel(
      this.api,
      {
        form: document.getElementById('trade-form'),
        symbol: document.getElementById('trade-symbol'),
        qty: document.getElementById('trade-qty'),
        sideToggle: document.getElementById('side-toggle'),
        submit: document.getElementById('trade-submit'),
        quotePrice: document.getElementById('quote-price'),
        quoteTotal: document.getElementById('quote-total'),
        quoteLabel: document.getElementById('quote-label'),
        quotePosition: document.getElementById('quote-position'),
        error: document.getElementById('trade-error'),
      },
      {
        onExecuted: (result) => {
          this.toast(`${result.transaction.side} ${result.transaction.quantity} ${result.transaction.symbol} @ ${money(result.transaction.price)}`);
          this.refreshPortfolio();
          this.refreshPerformance();
        },
      }
    );

    document.getElementById('user-select').addEventListener('change', (e) => {
      this.userId = Number(e.target.value);
      localStorage.setItem('sim.userId', String(this.userId));
      this.refreshAll();
    });

    document.getElementById('new-user-btn').addEventListener('click', async () => {
      const name = prompt('New account name:');
      if (!name) return;
      try {
        const { user } = await this.api.post('/api/users', { username: name });
        this.userId = user.id;
        localStorage.setItem('sim.userId', String(user.id));
        await this.loadUsers();
        this.refreshAll();
        this.toast(`Created account ${user.username} with ${money(user.cash)}`);
      } catch (err) {
        this.toast(err.message, true);
      }
    });

    document.getElementById('market-body').addEventListener('click', (e) => {
      const btn = e.target.closest('[data-action]');
      if (!btn) return;
      this.tradePanel.setSymbol(btn.dataset.symbol);
      this.tradePanel.setSide(btn.dataset.action === 'buy' ? 'BUY' : 'SELL');
      document.getElementById('trade-symbol').scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  }

  toast(message, isError = false) {
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = `toast${isError ? ' error' : ''}`;
    el.hidden = false;
    clearTimeout(this._toastTimer);
    this._toastTimer = setTimeout(() => (el.hidden = true), 3200);
  }

  async loadUsers() {
    const { users } = await this.api.get('/api/users');
    const select = document.getElementById('user-select');
    select.innerHTML = users.map((u) => `<option value="${u.id}">${esc(u.username)} · ${money(u.cash)}</option>`).join('');
    const saved = Number(localStorage.getItem('sim.userId'));
    this.userId = users.some((u) => u.id === saved) ? saved : users[0]?.id ?? null;
    select.value = String(this.userId);
  }

  async refreshMarket() {
    const data = await this.api.get('/api/market');
    this.stocks = data.stocks;
    this.marketTable.update(data.stocks);
    this.tradePanel.setStocks(data.stocks);
    document.getElementById('market-updated').textContent =
      `tick #${data.tick} · updated ${new Date().toLocaleTimeString()}`;
    document.getElementById('market-status').textContent = 'market open · simulating';
    document.getElementById('live-dot').classList.add('live');
  }

  async refreshPortfolio() {
    const data = await this.api.get(`/api/portfolio?userId=${this.userId}`);
    this.portfolioPanel.render(data.portfolio);
    this.activity.render(data.transactions);
    this.tradePanel.setAccount({
      cash: data.portfolio.cash,
      positions: data.portfolio.positions,
    });
    const select = document.getElementById('user-select');
    const option = select.querySelector(`option[value="${this.userId}"]`);
    if (option) option.textContent = `${data.portfolio.user.username} · ${money(data.portfolio.cash)}`;
  }

  async refreshPerformance() {
    const data = await this.api.get(`/api/performance?userId=${this.userId}`);
    this.chart.setData(data.history);
    if (data.history.length > 1) {
      document.getElementById('perf-range').textContent =
        `${data.history.length} samples since ${data.history[0].t.slice(0, 16)}`;
    }
  }

  async refreshAll() {
    await Promise.all([this.refreshMarket(), this.refreshPortfolio(), this.refreshPerformance()]);
  }

  async start() {
    try {
      await this.loadUsers();
      await this.refreshAll();
    } catch (err) {
      this.toast(`Failed to load: ${err.message}`, true);
    }
    setInterval(() => this.refreshMarket().catch(() => {}), 3000);
    setInterval(() => this.refreshPortfolio().catch(() => {}), 10_000);
    setInterval(() => this.refreshPerformance().catch(() => {}), 15_000);
  }
}

window.app = new App();
window.app.start();
