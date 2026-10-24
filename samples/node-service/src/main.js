// Minimal service implementing the bundle contract:
//   bin/node-service run          serves HTTP on APP_PORT
//   bin/node-service healthcheck  (passed through as --healthcheck) exits 0 when healthy
import { createServer } from 'node:http';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import ms from 'ms';
import { greeting } from './greeting.js';

const port = Number(process.env.APP_PORT ?? 8080);

if (process.argv.includes('--healthcheck')) {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(2000) });
    process.exit(res.ok ? 0 : 1);
  } catch {
    process.exit(1);
  }
}

const log = (line) => {
  if (process.env.APP_LOG_TARGET === 'file') {
    const dir = process.env.APP_LOG_DIR ?? 'logs';
    mkdirSync(dir, { recursive: true });
    appendFileSync(join(dir, 'node-service.log'), line + '\n');
  } else {
    console.log(line);
  }
};

const started = Date.now();
createServer((req, res) => {
  if (req.url === '/health') {
    res.end('UP\n');
    return;
  }
  res.end(`${greeting(process.env.APP_NAME ?? 'node-service')}, up ${ms(Date.now() - started)}\n`);
}).listen(port, '0.0.0.0', () => log(`node-service listening on ${port} (node ${process.version})`));
