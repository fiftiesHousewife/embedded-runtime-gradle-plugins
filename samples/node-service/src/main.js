// Minimal service implementing the launcher contract:
//   bin/node-service run          serves HTTP on APP_PORT
//   bin/node-service healthcheck  (passed through as --healthcheck) exits 0 when healthy
//   SIGTERM or SIGINT             stops taking connections, finishes those in flight and exits
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
const server = createServer((req, res) => {
  if (req.url === '/health') {
    res.end('UP\n');
    return;
  }
  res.end(`${greeting(process.env.APP_NAME ?? 'node-service')}, up ${ms(Date.now() - started)}\n`);
}).listen(port, '0.0.0.0', () => log(`node-service listening on ${port} (node ${process.version})`));

// In a container the launcher execs node as PID 1, and Linux gives PID 1 no default action for
// SIGTERM: without a handler, `docker stop` and Kubernetes wait out their grace period and kill it.
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.once(signal, () => {
    log(`node-service stopping on ${signal}`);
    server.close(() => process.exit(0));
  });
}
