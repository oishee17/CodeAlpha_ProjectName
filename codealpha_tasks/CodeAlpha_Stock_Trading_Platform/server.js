import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Database } from './lib/database.js';
import { Market, AccountService, PerformanceTracker } from './lib/market.js';
import { TradingService } from './lib/trading.js';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(ROOT, 'public');
const DB_PATH = path.join(ROOT, 'data', 'trading.db');
// PORT=0 in some shells means "no port set", so only honour a real port number.
const envPort = Number(process.env.PORT);
const PORT = Number.isInteger(envPort) && envPort > 1024 ? envPort : 3000;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

/**
 * App — wires the persistence layer, the market simulation and the trading
 * service together, and exposes them over HTTP as a JSON API.
 */
class App {
  constructor() {
    this.db = new Database(DB_PATH);
    this.market = new Market(this.db);
    this.accounts = new AccountService(this.db, this.market);
    this.accounts.ensureDefaults();
    this.trading = new TradingService(this.db, this.accounts, this.market);
    this.tracker = new PerformanceTracker(this.db, this.market);
    this.startedAt = Date.now();
  }

  start() {
    this.market.start();
    this.tracker.start();
  }

  activeUser(req) {
    const raw = new URL(req.url, 'http://localhost').searchParams.get('userId');
    const users = this.accounts.list();
    const id = raw ? Number(raw) : users[0]?.id;
    return users.find((u) => u.id === id) ?? users[0];
  }

  async handleApi(req, res, url) {
    const { pathname } = url;
    const method = req.method;

    if (method === 'GET' && pathname === '/api/market') {
      return this.json(res, {
        tick: this.market.tickCount,
        stocks: this.market.list().map((s) => s.toJSON()),
        serverTime: Date.now(),
      });
    }

    if (method === 'GET' && pathname === '/api/users') {
      return this.json(res, { users: this.accounts.list() });
    }

    if (method === 'POST' && pathname === '/api/users') {
      const body = await this.readBody(req);
      const user = this.accounts.create(body.username);
      return this.json(res, { user }, 201);
    }

    if (method === 'GET' && pathname === '/api/portfolio') {
      const user = this.activeUser(req);
      if (!user) return this.json(res, { error: 'No users exist yet' }, 404);
      const portfolio = this.accounts.portfolio(user.id);
      return this.json(res, {
        portfolio: portfolio.toJSON(),
        transactions: this.accounts.transactions(user.id, 25),
      });
    }

    if (method === 'GET' && pathname === '/api/transactions') {
      const user = this.activeUser(req);
      if (!user) return this.json(res, { error: 'No users exist yet' }, 404);
      return this.json(res, { transactions: this.accounts.transactions(user.id, 50) });
    }

    if (method === 'GET' && pathname === '/api/performance') {
      const user = this.activeUser(req);
      if (!user) return this.json(res, { error: 'No users exist yet' }, 404);
      return this.json(res, { history: this.accounts.history(user.id) });
    }

    if (method === 'POST' && pathname === '/api/trade') {
      const body = await this.readBody(req);
      try {
        const result = this.trading.execute(body);
        return this.json(res, result);
      } catch (err) {
        return this.json(res, { error: err.message }, 400);
      }
    }

    return this.json(res, { error: 'Not found' }, 404);
  }

  json(res, payload, status = 200) {
    const body = JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(body);
  }

  readBody(req) {
    return new Promise((resolve, reject) => {
      let data = '';
      req.on('data', (chunk) => {
        data += chunk;
        if (data.length > 64 * 1024) reject(new Error('Payload too large'));
      });
      req.on('end', () => {
        if (!data) return resolve({});
        try {
          resolve(JSON.parse(data));
        } catch {
          reject(new Error('Invalid JSON body'));
        }
      });
      req.on('error', reject);
    });
  }

  serveStatic(req, res, url) {
    const relative = decodeURIComponent(url.pathname) === '/' ? 'index.html' : url.pathname.slice(1);
    const filePath = path.join(PUBLIC_DIR, relative);
    if (!filePath.startsWith(PUBLIC_DIR)) {
      res.writeHead(403);
      return res.end('Forbidden');
    }
    fs.readFile(filePath, (err, buf) => {
      if (err) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('Not found');
      }
      const ext = path.extname(filePath).toLowerCase();
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      res.end(buf);
    });
  }
}

const app = new App();

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  try {
    if (url.pathname.startsWith('/api/')) {
      await app.handleApi(req, res, url);
    } else {
      app.serveStatic(req, res, url);
    }
  } catch (err) {
    app.json(res, { error: err.message || 'Server error' }, 500);
  }
});

app.start();
server.listen(PORT, '127.0.0.1', () => {
  console.log(`Stock trading sim running at http://localhost:${PORT}`);
  console.log(`Database: ${DB_PATH}`);
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    app.market.stop();
    app.tracker.stop();
    app.db.close();
    server.close(() => process.exit(0));
  });
}
