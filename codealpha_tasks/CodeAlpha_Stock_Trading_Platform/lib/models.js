/**
 * Domain models for the trading simulation.
 *
 * Stock       — a tradable symbol plus its simulated price behaviour.
 * Holding     — one user's position in one symbol (quantity + average cost).
 * User        — an account with cash and a set of holdings.
 * Transaction — an immutable record of a completed buy or sell.
 * Portfolio   — a User viewed against the Market: valuation, P&L, allocation.
 */

// Box–Muller transform: normal sample for the price random walk.
function gaussian() {
  let u = 0;
  let v = 0;
  while (u === 0) u = Math.random();
  while (v === 0) v = Math.random();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

const HISTORY_LIMIT = 60;

/** A tradable company/ETF with a live simulated price. */
export class Stock {
  constructor({ symbol, name, price, prevClose, dayOpen, dayHigh, dayLow, volatility, volume = 0, history = [] }) {
    this.symbol = symbol;
    this.name = name;
    this.price = price;
    this.prevClose = prevClose;
    this.dayOpen = dayOpen;
    this.dayHigh = dayHigh;
    this.dayLow = dayLow;
    this.volatility = volatility;
    this.volume = volume;
    this.history = history.length ? [...history] : [price];
  }

  static fromRow(row) {
    return new Stock({
      symbol: row.symbol,
      name: row.name,
      price: row.price,
      prevClose: row.prev_close,
      dayOpen: row.day_open,
      dayHigh: row.day_high,
      dayLow: row.day_low,
      volatility: row.volatility,
      volume: row.volume,
    });
  }

  /** Advance the simulated market by one tick (random walk with mild drift). */
  tick() {
    const drift = 0.00004;
    const shock = this.volatility * gaussian();
    const next = this.price * Math.exp(drift + shock);
    this.price = Math.max(0.01, Math.round(next * 100) / 100);
    if (this.price > this.dayHigh) this.dayHigh = this.price;
    if (this.price < this.dayLow) this.dayLow = this.price;
    this.volume += Math.floor(Math.random() * 50_000) + 1_000;
    this.history.push(this.price);
    if (this.history.length > HISTORY_LIMIT) this.history.shift();
    return this.price;
  }

  get change() {
    return this.price - this.prevClose;
  }

  get changePercent() {
    return this.prevClose === 0 ? 0 : ((this.price - this.prevClose) / this.prevClose) * 100;
  }

  get intradayChangePercent() {
    return this.dayOpen === 0 ? 0 : ((this.price - this.dayOpen) / this.dayOpen) * 100;
  }

  toJSON() {
    return {
      symbol: this.symbol,
      name: this.name,
      price: this.price,
      prevClose: this.prevClose,
      dayOpen: this.dayOpen,
      dayHigh: this.dayHigh,
      dayLow: this.dayLow,
      volume: this.volume,
      change: Math.round(this.change * 100) / 100,
      changePercent: Math.round(this.changePercent * 100) / 100,
      history: this.history,
    };
  }
}

/** A user's position in a single symbol. */
export class Holding {
  constructor({ symbol, quantity = 0, avgCost = 0 }) {
    this.symbol = symbol;
    this.quantity = quantity;
    this.avgCost = avgCost;
  }

  get costBasis() {
    return this.quantity * this.avgCost;
  }

  /** Merge a buy into this position (weighted-average cost). */
  add(quantity, price) {
    const totalCost = this.costBasis + quantity * price;
    this.quantity += quantity;
    this.avgCost = this.quantity === 0 ? 0 : totalCost / this.quantity;
  }

  /** Remove shares from this position; returns realized P&L for the sale. */
  remove(quantity, price) {
    const realizedPnl = (price - this.avgCost) * quantity;
    this.quantity -= quantity;
    if (this.quantity <= 0) {
      this.quantity = 0;
      this.avgCost = 0;
    }
    return realizedPnl;
  }

  marketValue(currentPrice) {
    return this.quantity * currentPrice;
  }

  unrealizedPnl(currentPrice) {
    return (currentPrice - this.avgCost) * this.quantity;
  }

  toJSON(currentPrice) {
    const value = this.marketValue(currentPrice);
    const pnl = this.unrealizedPnl(currentPrice);
    return {
      symbol: this.symbol,
      quantity: this.quantity,
      avgCost: round2(this.avgCost),
      costBasis: round2(this.costBasis),
      marketValue: round2(value),
      unrealizedPnl: round2(pnl),
      unrealizedPnlPercent:
        this.costBasis === 0 ? 0 : round2((pnl / this.costBasis) * 100),
      price: currentPrice,
    };
  }
}

/** A trading account. */
export class User {
  constructor({ id, username, cash, holdings = [] }) {
    this.id = id;
    this.username = username;
    this.cash = cash;
    this.holdings = new Map();
    for (const h of holdings) {
      this.holdings.set(h.symbol, h instanceof Holding ? h : new Holding(h));
    }
  }

  holding(symbol) {
    return this.holdings.get(symbol) ?? null;
  }

  get ownedSymbols() {
    return [...this.holdings.keys()];
  }

  /** Records a completed buy (mutates cash and holdings). */
  applyBuy(symbol, quantity, price) {
    this.cash -= quantity * price;
    const holding = this.holdings.get(symbol) ?? new Holding({ symbol });
    holding.add(quantity, price);
    this.holdings.set(symbol, holding);
  }

  /** Records a completed sell; returns realized P&L. */
  applySell(symbol, quantity, price) {
    const holding = this.holdings.get(symbol);
    if (!holding) throw new Error(`No position in ${symbol}`);
    this.cash += quantity * price;
    const realizedPnl = holding.remove(quantity, price);
    if (holding.quantity === 0) this.holdings.delete(symbol);
    return realizedPnl;
  }

  toJSON() {
    return { id: this.id, username: this.username, cash: round2(this.cash) };
  }
}

/** An immutable record of one completed trade. */
export class Transaction {
  constructor({ id, userId, symbol, side, quantity, price, total, realizedPnl = null, createdAt = null }) {
    this.id = id;
    this.userId = userId;
    this.symbol = symbol;
    this.side = side;
    this.quantity = quantity;
    this.price = price;
    this.total = total;
    this.realizedPnl = realizedPnl;
    this.createdAt = createdAt;
  }

  static fromRow(row) {
    return new Transaction({
      id: row.id,
      userId: row.user_id,
      symbol: row.symbol,
      side: row.side,
      quantity: row.quantity,
      price: row.price,
      total: row.total,
      realizedPnl: row.realized_pnl,
      createdAt: row.created_at,
    });
  }

  toJSON() {
    return {
      id: this.id,
      symbol: this.symbol,
      side: this.side,
      quantity: this.quantity,
      price: round2(this.price),
      total: round2(this.total),
      realizedPnl: this.realizedPnl === null ? null : round2(this.realizedPnl),
      createdAt: this.createdAt,
    };
  }
}

/** A User valued against the current Market. */
export class Portfolio {
  constructor(user, market) {
    this.user = user;
    this.market = market;
  }

  positions() {
    return [...this.user.holdings.values()]
      .filter((h) => h.quantity > 0)
      .map((h) => h.toJSON(this.market.priceOf(h.symbol)))
      .sort((a, b) => b.marketValue - a.marketValue);
  }

  get positionsValue() {
    return this.positions().reduce((sum, p) => sum + p.marketValue, 0);
  }

  get costBasis() {
    return this.positions().reduce((sum, p) => sum + p.costBasis, 0);
  }

  get unrealizedPnl() {
    return this.positions().reduce((sum, p) => sum + p.unrealizedPnl, 0);
  }

  get totalValue() {
    return this.user.cash + this.positionsValue;
  }

  get totalPnl() {
    return this.totalValue - this.startingCapital;
  }

  /**
   * Starting capital, derived exactly from the account's cash and its trade
   * history: cash + spent on buys - received from sells.
   */
  get startingCapital() {
    let invested = 0;
    let proceeds = 0;
    for (const t of this.transactions || []) {
      if (t.side === 'BUY') invested += t.total;
      else proceeds += t.total;
    }
    return this.user.cash + invested - proceeds;
  }

  toJSON() {
    const positions = this.positions();
    return {
      user: this.user.toJSON(),
      cash: round2(this.user.cash),
      positions,
      positionsValue: round2(this.positionsValue),
      costBasis: round2(this.costBasis),
      unrealizedPnl: round2(this.unrealizedPnl),
      unrealizedPnlPercent:
        this.costBasis === 0 ? 0 : round2((this.unrealizedPnl / this.costBasis) * 100),
      totalValue: round2(this.totalValue),
      totalPnl: round2(this.totalPnl),
      totalPnlPercent:
        this.startingCapital === 0
          ? 0
          : round2((this.totalPnl / this.startingCapital) * 100),
    };
  }
}

export function round2(n) {
  return Math.round(n * 100) / 100;
}
