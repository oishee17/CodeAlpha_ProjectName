import { Transaction, round2 } from './models.js';

/**
 * TradingService — the single place where buy/sell operations happen.
 *
 * Validates an order against the live market and the user's account, applies
 * it in memory, then persists the resulting cash, holding and transaction
 * rows together. Any invalid order throws with a user-facing message and
 * changes nothing.
 */
export class TradingService {
  constructor(db, accounts, market) {
    this.db = db;
    this.accounts = accounts;
    this.market = market;
  }

  /**
   * @param {{userId:number, symbol:string, side:'BUY'|'SELL', quantity:number}} order
   * @returns {{transaction: object, portfolio: object}}
   */
  execute(order) {
    const userId = Number(order.userId);
    const symbol = String(order.symbol || '').toUpperCase();
    const side = String(order.side || '').toUpperCase();
    const quantity = Number(order.quantity);

    if (!Number.isInteger(userId)) throw new Error('A valid user is required');
    if (!['BUY', 'SELL'].includes(side)) throw new Error('Side must be BUY or SELL');
    if (!Number.isInteger(quantity) || quantity <= 0) {
      throw new Error('Quantity must be a positive whole number');
    }

    const stock = this.market.get(symbol);
    if (!stock) throw new Error(`Unknown symbol "${symbol}"`);

    const user = this.accounts.get(userId);
    const price = stock.price;
    let sellPnl = null;

    if (side === 'BUY') {
      const cost = price * quantity;
      if (cost > user.cash + 1e-9) {
        throw new Error(
          `Insufficient cash: need $${round2(cost).toFixed(2)}, have $${round2(user.cash).toFixed(2)}`
        );
      }
      user.applyBuy(symbol, quantity, price);
      this.db.setCash(userId, user.cash);
      const holding = user.holding(symbol);
      this.db.saveHolding(userId, symbol, holding.quantity, holding.avgCost);
    } else {
      const holding = user.holding(symbol);
      if (!holding || holding.quantity < quantity) {
        const owned = holding ? holding.quantity : 0;
        throw new Error(`Insufficient shares of ${symbol}: own ${owned}, tried to sell ${quantity}`);
      }
      sellPnl = user.applySell(symbol, quantity, price);
      this.db.setCash(userId, user.cash);
      const remaining = user.holding(symbol);
      this.db.saveHolding(userId, symbol, remaining?.quantity ?? 0, remaining?.avgCost ?? 0);
    }

    const total = round2(price * quantity);
    const id = this.db.insertTransaction({
      userId,
      symbol,
      side,
      quantity,
      price,
      total,
      realizedPnl: sellPnl === null ? null : round2(sellPnl),
    });

    const portfolio = this.accounts.portfolio(userId);
    const transaction = new Transaction({
      id,
      userId,
      symbol,
      side,
      quantity,
      price,
      total,
      realizedPnl: sellPnl === null ? null : round2(sellPnl),
      createdAt: new Date().toISOString().replace('T', ' ').slice(0, 19),
    });

    return { transaction: transaction.toJSON(), portfolio: portfolio.toJSON() };
  }
}
