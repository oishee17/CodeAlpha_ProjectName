import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Database — persistence layer for the trading simulation.
 *
 * Owns the SQLite connection and every query the app runs. All reads/writes
 * for users, stocks, holdings, transactions and portfolio snapshots go
 * through this class, so the rest of the app never talks SQL directly.
 */
export class Database {
  constructor(filePath = 'data/trading.db') {
    if (filePath !== ':memory:') {
      fs.mkdirSync(path.dirname(filePath), { recursive: true });
    }
    this.db = new DatabaseSync(filePath);
    this.db.exec('PRAGMA foreign_keys = ON;');
    this.#createSchema();
  }

  #createSchema() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS users (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        username   TEXT NOT NULL UNIQUE,
        cash       REAL NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS stocks (
        symbol      TEXT PRIMARY KEY,
        name        TEXT NOT NULL,
        price       REAL NOT NULL,
        prev_close  REAL NOT NULL,
        day_open    REAL NOT NULL,
        day_high    REAL NOT NULL,
        day_low     REAL NOT NULL,
        volatility  REAL NOT NULL,
        volume      INTEGER NOT NULL DEFAULT 0,
        updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS holdings (
        user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        symbol    TEXT NOT NULL REFERENCES stocks(symbol),
        quantity  INTEGER NOT NULL,
        avg_cost  REAL NOT NULL,
        PRIMARY KEY (user_id, symbol)
      );

      CREATE TABLE IF NOT EXISTS transactions (
        id           INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        symbol       TEXT NOT NULL,
        side         TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
        quantity     INTEGER NOT NULL CHECK (quantity > 0),
        price        REAL NOT NULL,
        total        REAL NOT NULL,
        realized_pnl REAL,
        created_at   TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS portfolio_snapshots (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        portfolio_value REAL NOT NULL,
        cash            REAL NOT NULL,
        created_at      TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE INDEX IF NOT EXISTS idx_txn_user ON transactions(user_id, id DESC);
      CREATE INDEX IF NOT EXISTS idx_snap_user ON portfolio_snapshots(user_id, id);
    `);
  }

  // ---- Users ---------------------------------------------------------------

  createUser(username, startingCash) {
    const stmt = this.db.prepare(
      'INSERT INTO users (username, cash) VALUES (?, ?) RETURNING id, username, cash'
    );
    return stmt.get(username, startingCash);
  }

  getUser(id) {
    return this.db.prepare('SELECT id, username, cash FROM users WHERE id = ?').get(id);
  }

  findUserByName(username) {
    return this.db.prepare('SELECT id, username, cash FROM users WHERE username = ?').get(username);
  }

  listUsers() {
    return this.db.prepare('SELECT id, username, cash FROM users ORDER BY id').all();
  }

  setCash(userId, cash) {
    this.db.prepare('UPDATE users SET cash = ? WHERE id = ?').run(cash, userId);
  }

  // ---- Stocks --------------------------------------------------------------

  upsertStock(stock) {
    this.db
      .prepare(
        `INSERT INTO stocks (symbol, name, price, prev_close, day_open, day_high, day_low, volatility, volume, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
         ON CONFLICT(symbol) DO UPDATE SET
           price = excluded.price, day_high = excluded.day_high, day_low = excluded.day_low,
           volume = excluded.volume, updated_at = excluded.updated_at`
      )
      .run(
        stock.symbol,
        stock.name,
        stock.price,
        stock.prevClose,
        stock.dayOpen,
        stock.dayHigh,
        stock.dayLow,
        stock.volatility,
        stock.volume
      );
  }

  listStocks() {
    return this.db
      .prepare(
        'SELECT symbol, name, price, prev_close, day_open, day_high, day_low, volatility, volume FROM stocks ORDER BY symbol'
      )
      .all();
  }

  // ---- Holdings ------------------------------------------------------------

  listHoldings(userId) {
    return this.db
      .prepare('SELECT symbol, quantity, avg_cost FROM holdings WHERE user_id = ? ORDER BY symbol')
      .all(userId);
  }

  saveHolding(userId, symbol, quantity, avgCost) {
    if (quantity <= 0) {
      this.db.prepare('DELETE FROM holdings WHERE user_id = ? AND symbol = ?').run(userId, symbol);
      return;
    }
    this.db
      .prepare(
        `INSERT INTO holdings (user_id, symbol, quantity, avg_cost) VALUES (?, ?, ?, ?)
         ON CONFLICT(user_id, symbol) DO UPDATE SET quantity = excluded.quantity, avg_cost = excluded.avg_cost`
      )
      .run(userId, symbol, quantity, avgCost);
  }

  // ---- Transactions --------------------------------------------------------

  insertTransaction({ userId, symbol, side, quantity, price, total, realizedPnl = null }) {
    const info = this.db
      .prepare(
        `INSERT INTO transactions (user_id, symbol, side, quantity, price, total, realized_pnl)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .run(userId, symbol, side, quantity, price, total, realizedPnl);
    return Number(info.lastInsertRowid);
  }

  listTransactions(userId, limit = 50) {
    return this.db
      .prepare(
        `SELECT id, symbol, side, quantity, price, total, realized_pnl, created_at
         FROM transactions WHERE user_id = ? ORDER BY id DESC LIMIT ?`
      )
      .all(userId, limit);
  }

  // ---- Portfolio snapshots (performance over time) -------------------------

  insertSnapshot(userId, portfolioValue, cash) {
    this.db
      .prepare(
        'INSERT INTO portfolio_snapshots (user_id, portfolio_value, cash) VALUES (?, ?, ?)'
      )
      .run(userId, portfolioValue, cash);
    // Keep history bounded: newest 1440 points (~12h at 30s intervals) per user.
    this.db
      .prepare(
        `DELETE FROM portfolio_snapshots
          WHERE user_id = ? AND id NOT IN (
            SELECT id FROM portfolio_snapshots WHERE user_id = ? ORDER BY id DESC LIMIT 1440
          )`
      )
      .run(userId, userId);
  }

  listSnapshots(userId) {
    return this.db
      .prepare(
        'SELECT portfolio_value, cash, created_at FROM portfolio_snapshots WHERE user_id = ? ORDER BY id'
      )
      .all(userId);
  }

  close() {
    this.db.close();
  }
}
