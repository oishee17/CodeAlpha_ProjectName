import { Database } from './database.js';
import { Stock, Holding, User, Portfolio, round2 } from './models.js';

/** Seed universe: symbol, display name, starting price, per-tick volatility. */
const SEED_STOCKS = [
  ['AAPL', 'Apple Inc.', 224.5, 0.0018],
  ['MSFT', 'Microsoft Corp.', 418.2, 0.0016],
  ['GOOGL', 'Alphabet Inc.', 165.7, 0.002],
  ['AMZN', 'Amazon.com Inc.', 186.3, 0.0021],
  ['NVDA', 'NVIDIA Corp.', 131.9, 0.0034],
  ['TSLA', 'Tesla Inc.', 248.6, 0.0042],
  ['META', 'Meta Platforms', 561.4, 0.0024],
  ['JPM', 'JPMorgan Chase', 212.8, 0.0013],
  ['KO', 'Coca-Cola Co.', 71.4, 0.0009],
  ['SPY', 'S&P 500 ETF', 571.3, 0.001],
];

const DEFAULT_CASH = 100_000;

/**
 * Market — the collection of tradable stocks and the simulation clock.
 * Loads (or seeds) the universe from SQLite and advances every price on a timer.
 */
export class Market {
  constructor(db, tickMs = 3000) {
    this.db = db;
    this.tickMs = tickMs;
    this.stocks = new Map();
    this.tickCount = 0;
    this.timer = null;
    this.load();
  }

  load() {
    const rows = this.db.listStocks();
    if (rows.length === 0) {
      for (const [symbol, name, price, volatility] of SEED_STOCKS) {
        const open = round2(price + price * 0.003 * (Math.random() * 2 - 1));
        const prevClose = round2(price + price * 0.004 * (Math.random() * 2 - 1));
        const stock = new Stock({
          symbol,
          name,
          price,
          prevClose,
          dayOpen: open,
          dayHigh: round2(Math.max(price, open, prevClose) + price * 0.001 * Math.random()),
          dayLow: round2(Math.min(price, open, prevClose) - price * 0.001 * Math.random()),
          volume: Math.floor(Math.random() * 20_000_000) + 5_000_000,
          volatility,
        });
        this.stocks.set(symbol, stock);
        this.db.upsertStock(stock);
      }
    } else {
      for (const row of rows) this.stocks.set(row.symbol, Stock.fromRow(row));
    }
  }

  get(symbol) {
    return this.stocks.get(symbol) ?? null;
  }

  priceOf(symbol) {
    return this.stocks.get(symbol)?.price ?? 0;
  }

  list() {
    return [...this.stocks.values()];
  }

  /** Advance every stock one step and persist the new prices. */
  tick() {
    this.tickCount += 1;
    for (const stock of this.stocks.values()) {
      stock.tick();
      this.db.upsertStock(stock);
    }
    return this.tickCount;
  }

  start(onTick) {
    if (this.timer) return;
    this.timer = setInterval(() => {
      this.tick();
      onTick?.(this);
    }, this.tickMs);
    this.timer.unref?.();
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
  }
}

/**
 * PerformanceTracker — records the total value of every user's portfolio on a
 * timer so the app can chart performance over time.
 */
export class PerformanceTracker {
  constructor(db, market, intervalMs = 15_000) {
    this.db = db;
    this.market = market;
    this.intervalMs = intervalMs;
    this.timer = null;
  }

  snapshotAll() {
    for (const user of this.loadUsers()) {
      const portfolio = this.portfolioFor(user);
      this.db.insertSnapshot(user.id, round2(portfolio.totalValue), round2(user.cash));
    }
  }

  portfolioFor(user) {
    return new Portfolio(user, this.market);
  }

  loadUsers() {
    return this.db.listUsers().map((row) => this.loadUser(row.id));
  }

  loadUser(id) {
    const row = this.db.getUser(id);
    if (!row) return null;
    const holdings = this.db.listHoldings(id).map((h) => new Holding({ symbol: h.symbol, quantity: h.quantity, avgCost: h.avg_cost }));
    return new User({ id: row.id, username: row.username, cash: row.cash, holdings });
  }

  history(userId) {
    return this.db.listSnapshots(userId).map((s) => ({
      t: s.created_at,
      value: s.portfolio_value,
      cash: s.cash,
    }));
  }

  start() {
    if (this.timer) return;
    this.snapshotAll();
    this.timer = setInterval(() => this.snapshotAll(), this.intervalMs);
    this.timer.unref?.();
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
  }
}

/**
 * AccountService — creates users and loads them (with their holdings) back
 * out of the database.
 */
export class AccountService {
  constructor(db, market) {
    this.db = db;
    this.market = market;
  }

  ensureDefaults() {
    if (this.db.listUsers().length === 0) {
      this.db.createUser('demo', DEFAULT_CASH);
      this.db.createUser('trader2', DEFAULT_CASH);
    }
  }

  list() {
    return this.db.listUsers().map((r) => ({ id: r.id, username: r.username, cash: round2(r.cash) }));
  }

  create(username) {
    const name = String(username || '').trim().slice(0, 24);
    if (!name) throw new Error('Username is required');
    if (this.db.findUserByName(name)) throw new Error(`User "${name}" already exists`);
    const row = this.db.createUser(name, DEFAULT_CASH);
    return { id: row.id, username: row.username, cash: round2(row.cash) };
  }

  get(id) {
    const row = this.db.getUser(id);
    if (!row) throw new Error('User not found');
    const holdings = this.db
      .listHoldings(id)
      .map((h) => new Holding({ symbol: h.symbol, quantity: h.quantity, avgCost: h.avg_cost }));
    return new User({ id: row.id, username: row.username, cash: row.cash, holdings });
  }

  portfolio(id) {
    const user = this.get(id);
    const transactions = this.db.listTransactions(id).map((t) => t);
    const portfolio = new Portfolio(user, this.market);
    portfolio.transactions = transactions;
    return portfolio;
  }

  history(id) {
    return this.db.listSnapshots(id).map((s) => ({
      t: s.created_at,
      value: s.portfolio_value,
      cash: s.cash,
    }));
  }

  transactions(id, limit = 50) {
    return this.db.listTransactions(id, limit).map((row) => ({
      id: row.id,
      symbol: row.symbol,
      side: row.side,
      quantity: row.quantity,
      price: round2(row.price),
      total: round2(row.total),
      realizedPnl: row.realized_pnl === null ? null : round2(row.realized_pnl),
      createdAt: row.created_at,
    }));
  }
}
